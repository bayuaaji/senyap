import 'dart:async';
import 'dart:math';

import '../../core/api/api_error.dart';
import '../../core/realtime/realtime_client.dart';
import 'chat_models.dart';
import 'chat_repository.dart';
import 'chat_store.dart';

/// UUID v4 untuk `client_msg_id`.
String newClientMsgId([Random? random]) {
  final r = random ?? Random.secure();
  final b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  String hex(int from, int to) => b.sublist(from, to).map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${hex(0, 4)}-${hex(4, 6)}-${hex(6, 8)}-${hex(8, 10)}-${hex(10, 16)}';
}

/// Perubahan yang perlu digambar ulang UI.
sealed class ChatEvent {
  const ChatEvent();
}

/// Isi percakapan berubah (pesan baru, ack, status, tarik).
class ConversationChanged extends ChatEvent {
  const ConversationChanged(this.conversationId);
  final String conversationId;
}

/// Daftar inbox/permintaan perlu dimuat ulang (`conv.updated`, pesan baru).
class InboxChanged extends ChatEvent {
  const InboxChanged();
}

/// Pesan yang baru terkirim terdeteksi berisiko menyakiti diri: tampilkan kartu bantuan (FS-08).
class SupportCardSuggested extends ChatEvent {
  const SupportCardSuggested(this.conversationId);
  final String conversationId;
}

class PeerTyping extends ChatEvent {
  const PeerTyping(this.conversationId);
  final String conversationId;
}

/// Pesan yang ditolak server dan tidak akan dikirim ulang (misalnya percakapan ditutup).
class SendFailed extends ChatEvent {
  const SendFailed(this.conversationId, this.clientMsgId, this.error);
  final String conversationId;
  final String clientMsgId;
  final ApiError error;
}

/// Mesin Bisik di client:
///
/// * **Antrean offline**: pesan masuk [ChatStore] dulu, lalu dikirim berurutan. Tanpa `msg.ack` dalam
///   [ackTimeout] → dikirim ulang dengan `client_msg_id` yang sama (server mengembalikan ack yang sama).
///   Saat WebSocket putus, antrean dicoba lewat REST (fallback); bila itu juga gagal, menunggu tersambung lagi.
/// * **Urutan**: pesan diurutkan berdasarkan `seq`. `seq` melompat → gap fill (`after_seq`).
/// * **Reconnect**: kirim `last_seq` yang diketahui ke `/v1/conversations/sync`, isi kekosongan, lalu kirim antrean.
class ChatEngine {
  ChatEngine({
    required this.store,
    required this.repository,
    required this.transport,
    this.ackTimeout = const Duration(seconds: 5),
    this.retryInterval = const Duration(seconds: 10),
  });

  final ChatStore store;
  final ChatRepository repository;
  final RealtimeTransport transport;
  final Duration ackTimeout;
  final Duration retryInterval;

  final _events = StreamController<ChatEvent>.broadcast();
  final _acks = <String, Completer<SendAck?>>{};
  final _failed = <String, PendingMessage>{};
  final _subs = <StreamSubscription<Object?>>[];
  Future<void>? _flushing;
  var _flushAgain = false;
  Timer? _retryTimer;

  Stream<ChatEvent> get events => _events.stream;

  void start() {
    _subs.add(transport.frames.listen(_onFrame));
    _subs.add(transport.connection.listen((connected) {
      if (connected) unawaited(_onConnected());
    }));
    transport.start();
    _retryTimer = Timer.periodic(retryInterval, (_) => flush());
    unawaited(flush());
  }

  Future<void> stop() async {
    _retryTimer?.cancel();
    for (final s in _subs) {
      await s.cancel();
    }
    _subs.clear();
    await transport.stop();
  }

  // ------------------------------------------------------------------ kirim

  /// Simpan ke antrean lalu kirim. Kembali segera (pesan tampil ⏱).
  Future<String> sendText(String conversationId, String body, {String? clientMsgId}) =>
      _enqueue(conversationId, 'TEXT', body: body, clientMsgId: clientMsgId);

  Future<String> sendImage(String conversationId, String mediaId) =>
      _enqueue(conversationId, 'IMAGE', mediaId: mediaId);

  Future<String> _enqueue(String conversationId, String type, {String? body, String? mediaId, String? clientMsgId}) async {
    final id = clientMsgId ?? newClientMsgId();
    await store.enqueue(PendingMessage(
      clientMsgId: id,
      conversationId: conversationId,
      type: type,
      body: body,
      mediaId: mediaId,
      createdAt: DateTime.now(),
    ));
    _events.add(ConversationChanged(conversationId));
    unawaited(flush());
    return id;
  }

  /// Kirim seluruh antrean, satu per satu dan berurutan. Pemanggilan bersamaan digabung.
  Future<void> flush() {
    if (_flushing != null) {
      _flushAgain = true;
      return _flushing!;
    }
    final f = _flushLoop().whenComplete(() => _flushing = null);
    _flushing = f;
    return f;
  }

  Future<void> _flushLoop() async {
    do {
      _flushAgain = false;
      for (final p in await store.pending()) {
        final ok = await _sendOne(p);
        if (!ok) return; // tunggu tersambung / percobaan berikutnya; urutan dijaga
      }
    } while (_flushAgain);
  }

  /// `true` bila pesan selesai (tersimpan di server atau ditolak permanen).
  Future<bool> _sendOne(PendingMessage p) async {
    SendAck? ack;
    if (transport.isConnected) {
      final completer = _acks[p.clientMsgId] = Completer<SendAck?>();
      final sent = transport.send('msg.send', {
        'conversation_id': p.conversationId,
        'client_msg_id': p.clientMsgId,
        'type': p.type,
        'body': ?p.body,
        'media_id': ?p.mediaId,
      }, id: p.clientMsgId);
      if (sent) {
        ack = await completer.future.timeout(ackTimeout, onTimeout: () => null);
      }
      _acks.remove(p.clientMsgId);
      if (ack == null) {
        if (_failed.containsKey(p.clientMsgId)) return true;
        return false; // dikirim ulang dengan client_msg_id yang sama
      }
    } else {
      try {
        ack = await repository.send(p.conversationId,
            clientMsgId: p.clientMsgId, type: p.type, body: p.body, mediaId: p.mediaId);
      } on ApiError catch (e) {
        if (_isPermanent(e)) {
          await _fail(p, e);
          return true;
        }
        return false;
      }
    }
    await _confirm(p, ack);
    return true;
  }

  static bool _isPermanent(ApiError e) =>
      e.status != null && e.status! >= 400 && e.status! < 500 && e.status != 401 && e.status != 429;

  Future<void> _confirm(PendingMessage p, SendAck ack) async {
    await store.putMessages([
      ChatMessage(
        conversationId: p.conversationId,
        seq: ack.seq,
        clientMsgId: p.clientMsgId,
        mine: true,
        type: p.type,
        body: p.body,
        createdAt: ack.createdAt,
      ),
    ]);
    await store.removePending(p.clientMsgId);
    _events.add(ConversationChanged(p.conversationId));
    if (ack.supportCard) _events.add(SupportCardSuggested(p.conversationId));
  }

  Future<void> _fail(PendingMessage p, ApiError e) async {
    _failed[p.clientMsgId] = p;
    await store.removePending(p.clientMsgId);
    _events.add(SendFailed(p.conversationId, p.clientMsgId, e));
    _events.add(ConversationChanged(p.conversationId));
  }

  // ------------------------------------------------------------------ frame masuk

  Future<void> _onFrame(Map<String, dynamic> f) async {
    final d = (f['d'] as Map?)?.cast<String, dynamic>() ?? const {};
    switch (f['t']) {
      case 'msg.ack':
        _acks[d['client_msg_id']]?.complete(SendAck.fromJson(d));
      case 'error':
        final ref = d['ref_id'] as String?;
        if (ref != null && _acks.containsKey(ref)) {
          final e = ApiError(code: d['code'] as String? ?? 'INTERNAL', message: d['message'] as String? ?? '',
              status: _statusOf(d['code'] as String?));
          if (_isPermanent(e)) {
            final p = (await store.pending()).where((x) => x.clientMsgId == ref).firstOrNull;
            if (p != null) await _fail(p, e);
          }
          _acks[ref]?.complete(null);
        }
      case 'msg.new':
        await _onNewMessage(ChatMessage.fromJson(d));
      case 'msg.status':
        final conv = d['conversation_id'] as String;
        await store.setPeerStatus(conv,
            delivered: (d['delivered_up_to'] as num).toInt(), read: (d['read_up_to'] as num).toInt());
        _events.add(ConversationChanged(conv));
      case 'msg.retracted':
        final conv = d['conversation_id'] as String;
        await store.markRetracted(conv, (d['seq'] as num).toInt());
        _events.add(ConversationChanged(conv));
        _events.add(const InboxChanged());
      case 'typing':
        _events.add(PeerTyping(d['conversation_id'] as String));
      case 'conv.updated':
        _events.add(const InboxChanged());
        _events.add(ConversationChanged(d['conversation_id'] as String));
    }
  }

  static int? _statusOf(String? code) => switch (code) {
        'VALIDATION_FAILED' => 400,
        'NOT_FOUND' => 404,
        'FORBIDDEN' => 403,
        'CONVERSATION_CLOSED' || 'CONVERSATION_PENDING' => 409,
        'RATE_LIMITED' => 429,
        'UNAUTHENTICATED' => 401,
        _ => null,
      };

  Future<void> _onNewMessage(ChatMessage m) async {
    final conv = m.conversationId;
    final before = await store.contiguousUpTo(conv);
    await store.putMessages([m]);
    if (m.mine && m.clientMsgId != null) {
      // Dikirim dari device lain milik saya, atau ack datang setelah msg.new.
      await store.removePending(m.clientMsgId!);
    }
    if ((m.seq ?? 0) > before + 1) {
      await fillGap(conv, before);
    }
    _events.add(ConversationChanged(conv));
    _events.add(const InboxChanged());
    if (!m.mine) {
      final upTo = await store.contiguousUpTo(conv);
      transport.send('msg.delivered', {'conversation_id': conv, 'up_to_seq': upTo});
    }
  }

  /// Ambil pesan setelah [afterSeq] sampai habis (gap fill / sinkron).
  Future<void> fillGap(String conversationId, int afterSeq) async {
    var after = afterSeq;
    while (true) {
      final page = await repository.messages(conversationId, afterSeq: after, limit: 200);
      if (page.isEmpty) break;
      await store.putMessages(page);
      after = page.last.seq ?? after;
      if (page.length < 200) break;
    }
    _events.add(ConversationChanged(conversationId));
  }

  /// Saat (re)connect: sinkron `last_seq` lalu kirim antrean.
  Future<void> _onConnected() async {
    try {
      final known = await store.knownLastSeqs();
      final changed = await repository.sync(known);
      for (final e in changed.entries) {
        await fillGap(e.key, known[e.key] ?? 0);
      }
      if (changed.isNotEmpty) _events.add(const InboxChanged());
    } on ApiError {
      // Sinkron dicoba lagi pada reconnect berikutnya.
    }
    await flush();
  }

  // ------------------------------------------------------------------ baca

  /// Pesan percakapan untuk ditampilkan: yang tersimpan (urut seq) + antrean lokal (⏱) + yang gagal.
  Future<List<ChatMessage>> messagesOf(String conversationId) async {
    final saved = await store.messages(conversationId);
    final pending = await store.pending(conversationId);
    final savedIds = saved.map((m) => m.clientMsgId).whereType<String>().toSet();
    return [
      ...saved,
      for (final p in pending)
        if (!savedIds.contains(p.clientMsgId)) p.toMessage(),
      for (final p in _failed.values)
        if (p.conversationId == conversationId)
          ChatMessage(
            conversationId: p.conversationId,
            clientMsgId: p.clientMsgId,
            mine: true,
            type: p.type,
            body: p.body,
            createdAt: p.createdAt,
            failed: true,
          ),
    ];
  }

  /// Muat riwayat dari server (saat layar chat dibuka) lalu simpan lokal.
  Future<void> refresh(String conversationId) async {
    final local = await store.contiguousUpTo(conversationId);
    if (local == 0) {
      await store.putMessages(await repository.messages(conversationId, limit: 50));
    } else {
      await fillGap(conversationId, local);
    }
    _events.add(ConversationChanged(conversationId));
  }

  /// Riwayat lebih lama (scroll ke atas).
  Future<int> loadOlder(String conversationId, int beforeSeq) async {
    final page = await repository.messages(conversationId, beforeSeq: beforeSeq, limit: 50);
    await store.putMessages(page);
    _events.add(ConversationChanged(conversationId));
    return page.length;
  }

  /// Tandai dibaca sampai [upToSeq] (WS bila tersambung, REST bila tidak).
  Future<void> markRead(String conversationId, int upToSeq) async {
    if (!transport.send('msg.read', {'conversation_id': conversationId, 'up_to_seq': upToSeq})) {
      try {
        await repository.receipts(conversationId, readUpTo: upToSeq);
      } on ApiError {
        // dicoba lagi saat layar dibuka berikutnya
      }
    }
  }

  DateTime? _lastTypingSent;

  /// Indikator mengetik (throttle 1 per 3 detik; server juga membatasi).
  void typing(String conversationId) {
    final now = DateTime.now();
    if (_lastTypingSent != null && now.difference(_lastTypingSent!) < const Duration(seconds: 3)) return;
    _lastTypingSent = now;
    transport.send('typing', {'conversation_id': conversationId});
  }

  /// Hapus pesan gagal dari tampilan, atau kirim ulang dengan `client_msg_id` yang sama.
  Future<void> retryFailed(String clientMsgId) async {
    final p = _failed.remove(clientMsgId);
    if (p == null) return;
    await store.enqueue(p);
    unawaited(flush());
  }

  void discardFailed(String clientMsgId) {
    final p = _failed.remove(clientMsgId);
    if (p != null) _events.add(ConversationChanged(p.conversationId));
  }
}
