import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/api/api_error.dart';
import 'package:senyap/core/realtime/realtime_client.dart';
import 'package:senyap/features/messages/chat_engine.dart';
import 'package:senyap/features/messages/chat_models.dart';
import 'package:senyap/features/messages/chat_repository.dart';
import 'package:senyap/features/messages/chat_store.dart';

class FakeTransport implements RealtimeTransport {
  final _frames = StreamController<Map<String, dynamic>>.broadcast();
  final _connection = StreamController<bool>.broadcast();
  final sent = <Map<String, dynamic>>[];
  var connected = false;

  /// Bila diisi, setiap `msg.send` langsung dibalas lewat fungsi ini (mis. ack).
  Map<String, dynamic>? Function(Map<String, dynamic> frame)? autoReply;

  @override
  Stream<Map<String, dynamic>> get frames => _frames.stream;

  @override
  Stream<bool> get connection => _connection.stream;

  @override
  bool get isConnected => connected;

  @override
  bool send(String type, Map<String, dynamic> data, {String? id}) {
    if (!connected) return false;
    final f = {'t': type, 'id': id, 'd': data};
    sent.add(f);
    final reply = autoReply?.call(f);
    if (reply != null) scheduleMicrotask(() => _frames.add(reply));
    return true;
  }

  void setConnected(bool value) {
    connected = value;
    _connection.add(value);
  }

  void receive(Map<String, dynamic> frame) => _frames.add(frame);

  @override
  void start() {}

  @override
  Future<void> stop() async {}

  List<String> sentIds(String type) =>
      sent.where((f) => f['t'] == type).map((f) => (f['d'] as Map)['client_msg_id'] as String).toList();
}

class FakeRepo implements ChatRepository {
  var online = false;
  var nextSeq = 2;
  final restSent = <String>[];
  final messagesCalls = <(String, int?)>[];
  final server = <String, List<ChatMessage>>{};
  Map<String, int> syncResult = {};

  static const offline = ApiError(code: 'NETWORK', message: 'offline');

  @override
  Future<SendAck> send(String id, {required String clientMsgId, required String type, String? body, String? mediaId}) async {
    if (!online) throw offline;
    restSent.add(clientMsgId);
    return SendAck(clientMsgId: clientMsgId, conversationId: id, seq: nextSeq++, createdAt: DateTime.utc(2026, 11, 9));
  }

  @override
  Future<List<ChatMessage>> messages(String id, {int? beforeSeq, int? afterSeq, int limit = 50}) async {
    messagesCalls.add((id, afterSeq));
    return (server[id] ?? []).where((m) => afterSeq == null || m.seq! > afterSeq).toList();
  }

  @override
  Future<Map<String, int>> sync(Map<String, int> known) async => syncResult;

  @override
  Future<void> receipts(String id, {int? deliveredUpTo, int? readUpTo}) async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError(invocation.memberName.toString());

  @override
  Future<String> uploadImage(Uint8List bytes, String mimeType) => throw UnimplementedError();
}

ChatMessage theirs(String conv, int seq, [String? body]) => ChatMessage(
      conversationId: conv,
      seq: seq,
      mine: false,
      type: 'TEXT',
      body: body ?? 'pesan $seq',
      createdAt: DateTime.utc(2026, 11, 9),
    );

Map<String, dynamic> newFrame(ChatMessage m) => {'t': 'msg.new', 'd': m.toJson()};

void main() {
  late ChatStore store;
  late FakeTransport transport;
  late FakeRepo repo;
  late ChatEngine engine;

  setUp(() {
    store = ChatStore(NativeDatabase.memory());
    transport = FakeTransport();
    repo = FakeRepo();
    engine = ChatEngine(
      store: store,
      repository: repo,
      transport: transport,
      ackTimeout: const Duration(milliseconds: 50),
      retryInterval: const Duration(hours: 1),
    )..start();
  });

  tearDown(() async {
    await engine.stop();
    await store.close();
  });

  Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 300));

  test('5 pesan offline: antre, ack hilang, dikirim ulang dengan client_msg_id sama, sekali, berurutan', () async {
    const conv = '100';
    final ids = <String>[];
    for (var i = 0; i < 5; i++) {
      ids.add(await engine.sendText(conv, 'offline $i'));
    }
    await settle();
    expect((await store.pending()).map((p) => p.clientMsgId), ids, reason: 'tertahan di antrean lokal');
    expect((await engine.messagesOf(conv)).map((m) => m.delivery(peerDeliveredUpTo: 0, peerReadUpTo: 0)),
        everyElement(Delivery.sending));

    // Tersambung, tapi ack hilang (koneksi putus lagi sebelum ack).
    transport.setConnected(true);
    await settle();
    transport.setConnected(false);
    expect(transport.sentIds('msg.send').first, ids.first);

    // Tersambung lagi: server meng-ack. Kirim ulang memakai client_msg_id yang sama.
    final acked = <String, int>{};
    var seq = 2;
    transport.autoReply = (f) {
      final d = f['d'] as Map;
      final cmid = d['client_msg_id'] as String;
      acked.putIfAbsent(cmid, () => seq++);
      return {
        't': 'msg.ack',
        'id': f['id'],
        'd': {'client_msg_id': cmid, 'conversation_id': conv, 'seq': acked[cmid], 'created_at': '2026-11-09T00:00:00Z'},
      };
    };
    transport.setConnected(true);
    await settle();

    expect(await store.pending(), isEmpty);
    expect(transport.sentIds('msg.send').toSet(), ids.toSet(), reason: 'tidak ada client_msg_id baru');
    final msgs = await engine.messagesOf(conv);
    expect(msgs.map((m) => m.seq), [2, 3, 4, 5, 6]);
    expect(msgs.map((m) => m.body), ['offline 0', 'offline 1', 'offline 2', 'offline 3', 'offline 4']);
  });

  test('saat WebSocket putus, antrean dikirim lewat REST', () async {
    repo.online = true;
    final id = await engine.sendText('7', 'lewat rest');
    await settle();
    expect(repo.restSent, [id]);
    expect(await store.pending(), isEmpty);
    expect((await engine.messagesOf('7')).single.seq, 2);
  });

  test('seq melompat memicu gap fill', () async {
    const conv = '200';
    repo.server[conv] = [for (var s = 1; s <= 4; s++) theirs(conv, s)];
    transport.setConnected(true);
    transport.receive(newFrame(theirs(conv, 1)));
    await settle();
    transport.receive(newFrame(theirs(conv, 4)));
    await settle();
    expect(repo.messagesCalls, contains((conv, 1)));
    expect((await engine.messagesOf(conv)).map((m) => m.seq), [1, 2, 3, 4]);
    // msg.delivered sampai seq tertinggi yang berurutan
    final delivered = transport.sent.where((f) => f['t'] == 'msg.delivered').last['d'] as Map;
    expect(delivered['up_to_seq'], 4);
  });

  test('reconnect: sinkron last_seq lalu mengambil pesan yang tertinggal', () async {
    const conv = '300';
    await store.putMessages([theirs(conv, 1), theirs(conv, 2)]);
    repo.server[conv] = [for (var s = 1; s <= 5; s++) theirs(conv, s)];
    repo.syncResult = {conv: 5};
    transport.setConnected(true);
    await settle();
    expect(repo.messagesCalls, contains((conv, 2)));
    expect((await engine.messagesOf(conv)).map((m) => m.seq), [1, 2, 3, 4, 5]);
  });

  test('ditolak permanen (percakapan ditutup) → tidak dikirim ulang, ditandai gagal', () async {
    const conv = '400';
    final events = <ChatEvent>[];
    engine.events.listen(events.add);
    transport.autoReply = (f) => {
          't': 'error',
          'd': {'ref_id': f['id'], 'code': 'CONVERSATION_CLOSED', 'message': 'Percakapan ini sudah ditutup.'},
        };
    transport.setConnected(true);
    await engine.sendText(conv, 'halo?');
    await settle();
    expect(await store.pending(), isEmpty);
    expect(events.whereType<SendFailed>().single.error.code, 'CONVERSATION_CLOSED');
    final m = (await engine.messagesOf(conv)).single;
    expect(m.delivery(peerDeliveredUpTo: 0, peerReadUpTo: 0), Delivery.failed);
    expect(transport.sentIds('msg.send'), hasLength(1));
  });

  test('status pesan: ⏱ → ✓ → ✓✓ → dibaca', () {
    final m = ChatMessage(conversationId: '1', mine: true, type: 'TEXT', createdAt: DateTime(2026));
    expect(m.delivery(peerDeliveredUpTo: 0, peerReadUpTo: 0), Delivery.sending);
    final saved = m.copyWith(seq: 3);
    expect(saved.delivery(peerDeliveredUpTo: 2, peerReadUpTo: 0), Delivery.sent);
    expect(saved.delivery(peerDeliveredUpTo: 3, peerReadUpTo: 0), Delivery.delivered);
    expect(saved.delivery(peerDeliveredUpTo: 3, peerReadUpTo: 3), Delivery.read);
  });

  test('client_msg_id adalah UUID v4', () {
    final id = newClientMsgId(Random(1));
    expect(id, matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
  });

  test('backoff reconnect eksponensial dengan batas 30 detik', () {
    final b = Backoff(random: Random(0));
    final waits = [for (var i = 0; i < 8; i++) b.next().inMilliseconds];
    expect(waits.first, inInclusiveRange(800, 1200));
    expect(waits[3], inInclusiveRange(6400, 9600));
    expect(waits.last, inInclusiveRange(24000, 36000));
    b.reset();
    expect(b.next().inMilliseconds, inInclusiveRange(800, 1200));
  });

  test('tutup 1012 (deploy gateway): sambung ulang cepat dengan jeda acak 0,2–3 dtk', () {
    final r = Random(7);
    for (var i = 0; i < 50; i++) {
      final d = restartDelay(r);
      expect(d.inMilliseconds, inInclusiveRange(200, 3000));
    }
  });
}
