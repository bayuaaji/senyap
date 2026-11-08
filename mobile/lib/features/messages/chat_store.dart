import 'dart:convert';

import 'package:drift/drift.dart';

import 'chat_models.dart';

/// Pesan yang menunggu dikirim. [clientMsgId] tetap sama di setiap kirim ulang (idempotensi server).
class PendingMessage {
  const PendingMessage({
    required this.clientMsgId,
    required this.conversationId,
    required this.type,
    required this.createdAt,
    this.body,
    this.mediaId,
  });

  final String clientMsgId;
  final String conversationId;
  final String type;
  final String? body;
  final String? mediaId;
  final DateTime createdAt;

  ChatMessage toMessage() => ChatMessage(
        conversationId: conversationId,
        clientMsgId: clientMsgId,
        mine: true,
        type: type,
        body: body,
        createdAt: createdAt,
      );
}

/// Penyimpanan lokal Bisik (SQLite lewat Drift): antrean kirim offline, salinan pesan, dan `last_seq`
/// per percakapan untuk sinkron saat reconnect. Drift dipakai dengan SQL langsung (tanpa codegen).
class ChatStore extends GeneratedDatabase {
  ChatStore(super.executor);

  @override
  int get schemaVersion => 1;

  @override
  Iterable<TableInfo<Table, Object?>> get allTables => const [];

  @override
  MigrationStrategy get migration => MigrationStrategy(onCreate: (m) async {
        await customStatement('''
          CREATE TABLE outbox (
            client_msg_id   TEXT PRIMARY KEY,
            conversation_id TEXT NOT NULL,
            type            TEXT NOT NULL,
            body            TEXT,
            media_id        TEXT,
            created_at      INTEGER NOT NULL,
            ord             INTEGER NOT NULL
          )''');
        await customStatement('''
          CREATE TABLE messages (
            conversation_id TEXT NOT NULL,
            seq             INTEGER NOT NULL,
            json            TEXT NOT NULL,
            PRIMARY KEY (conversation_id, seq)
          )''');
        await customStatement('''
          CREATE TABLE conv_state (
            conversation_id TEXT PRIMARY KEY,
            peer_delivered  INTEGER NOT NULL DEFAULT 0,
            peer_read       INTEGER NOT NULL DEFAULT 0
          )''');
      });

  // ------------------------------------------------------------------ antrean kirim

  Future<void> enqueue(PendingMessage p) => customInsert(
        'INSERT OR IGNORE INTO outbox (client_msg_id, conversation_id, type, body, media_id, created_at, ord) '
        'VALUES (?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(ord), 0) + 1 FROM outbox))',
        variables: [
          Variable.withString(p.clientMsgId),
          Variable.withString(p.conversationId),
          Variable.withString(p.type),
          Variable(p.body),
          Variable(p.mediaId),
          Variable.withInt(p.createdAt.millisecondsSinceEpoch),
        ],
      );

  /// Antrean urut waktu masuk (urutan kirim = urutan ketik).
  Future<List<PendingMessage>> pending([String? conversationId]) async {
    final rows = await customSelect(
      'SELECT * FROM outbox ${conversationId == null ? '' : 'WHERE conversation_id = ?'} ORDER BY ord',
      variables: [if (conversationId != null) Variable.withString(conversationId)],
    ).get();
    return rows
        .map((r) => PendingMessage(
              clientMsgId: r.read<String>('client_msg_id'),
              conversationId: r.read<String>('conversation_id'),
              type: r.read<String>('type'),
              body: r.readNullable<String>('body'),
              mediaId: r.readNullable<String>('media_id'),
              createdAt: DateTime.fromMillisecondsSinceEpoch(r.read<int>('created_at')),
            ))
        .toList();
  }

  Future<void> removePending(String clientMsgId) => customStatement(
        'DELETE FROM outbox WHERE client_msg_id = ?',
        [clientMsgId],
      );

  // ------------------------------------------------------------------ pesan

  Future<void> putMessages(Iterable<ChatMessage> messages) => transaction(() async {
        for (final m in messages) {
          final seq = m.seq;
          if (seq == null) continue;
          await customStatement(
            'INSERT OR REPLACE INTO messages (conversation_id, seq, json) VALUES (?, ?, ?)',
            [m.conversationId, seq, jsonEncode(m.toJson())],
          );
        }
      });

  Future<List<ChatMessage>> messages(String conversationId, {int limit = 200}) async {
    final rows = await customSelect(
      'SELECT json FROM (SELECT json, seq FROM messages WHERE conversation_id = ? ORDER BY seq DESC LIMIT ?) ORDER BY seq',
      variables: [Variable.withString(conversationId), Variable.withInt(limit)],
    ).get();
    return rows.map((r) => ChatMessage.fromJson(jsonDecode(r.read<String>('json')) as Map<String, dynamic>)).toList();
  }

  Future<void> markRetracted(String conversationId, int seq) async {
    final rows = await customSelect('SELECT json FROM messages WHERE conversation_id = ? AND seq = ?',
        variables: [Variable.withString(conversationId), Variable.withInt(seq)]).get();
    if (rows.isEmpty) return;
    final m = ChatMessage.fromJson(jsonDecode(rows.first.read<String>('json')) as Map<String, dynamic>);
    await putMessages([m.copyWith(retracted: true)]);
  }

  /// Seq tertinggi yang semua seq di bawahnya sudah ada lokal (0 bila belum ada). Lompatan setelahnya = gap.
  Future<int> contiguousUpTo(String conversationId) async {
    final rows = await customSelect('SELECT seq FROM messages WHERE conversation_id = ? ORDER BY seq',
        variables: [Variable.withString(conversationId)]).get();
    var expected = 1;
    for (final r in rows) {
      final s = r.read<int>('seq');
      if (s != expected) break;
      expected++;
    }
    return expected - 1;
  }

  /// `last_seq` yang diketahui per percakapan (untuk sinkron saat reconnect).
  Future<Map<String, int>> knownLastSeqs() async {
    final ids = await customSelect('SELECT DISTINCT conversation_id FROM messages').get();
    return {
      for (final r in ids)
        r.read<String>('conversation_id'): await contiguousUpTo(r.read<String>('conversation_id')),
    };
  }

  // ------------------------------------------------------------------ status lawan bicara

  Future<void> setPeerStatus(String conversationId, {required int delivered, required int read}) => customStatement(
        'INSERT INTO conv_state (conversation_id, peer_delivered, peer_read) VALUES (?, ?, ?) '
        'ON CONFLICT(conversation_id) DO UPDATE SET '
        'peer_delivered = MAX(peer_delivered, excluded.peer_delivered), peer_read = MAX(peer_read, excluded.peer_read)',
        [conversationId, delivered, read],
      );

  Future<(int, int)> peerStatus(String conversationId) async {
    final rows = await customSelect('SELECT peer_delivered, peer_read FROM conv_state WHERE conversation_id = ?',
        variables: [Variable.withString(conversationId)]).get();
    if (rows.isEmpty) return (0, 0);
    return (rows.first.read<int>('peer_delivered'), rows.first.read<int>('peer_read'));
  }

  /// Saat keluar akun: hapus semua data lokal.
  Future<void> clearAll() async {
    await customStatement('DELETE FROM outbox');
    await customStatement('DELETE FROM messages');
    await customStatement('DELETE FROM conv_state');
  }
}
