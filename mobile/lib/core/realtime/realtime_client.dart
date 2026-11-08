import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

/// Jeda reconnect eksponensial dengan jitter: 1, 2, 4, … maksimal 30 detik (±20%).
class Backoff {
  Backoff({this.base = const Duration(seconds: 1), this.max = const Duration(seconds: 30), Random? random})
      : _random = random ?? Random();

  final Duration base;
  final Duration max;
  final Random _random;
  int _attempt = 0;

  Duration next() {
    final raw = base.inMilliseconds * pow(2, _attempt.clamp(0, 16));
    _attempt++;
    final capped = min(raw.toDouble(), max.inMilliseconds.toDouble());
    final jitter = capped * (0.8 + _random.nextDouble() * 0.4);
    return Duration(milliseconds: jitter.round());
  }

  void reset() => _attempt = 0;
}

/// Kode tutup 1012 (Service Restart): node gateway sedang di-deploy. Sambung ulang cepat dengan jeda acak
/// 0,2–3 detik (bukan backoff) supaya client tersebar ke node lain dan tidak menyerbu serentak.
const closeServiceRestart = 1012;

Duration restartDelay(Random random) => Duration(milliseconds: 200 + random.nextInt(2800));

/// Koneksi realtime ke gateway (functional spec FS-05 §5.5). Frame = `{t, id, d}`.
abstract interface class RealtimeTransport {
  Stream<Map<String, dynamic>> get frames;

  /// `true` saat tersambung, `false` saat putus.
  Stream<bool> get connection;
  bool get isConnected;

  /// Mengirim frame; `false` bila sedang tidak tersambung.
  bool send(String type, Map<String, dynamic> data, {String? id});
  void start();
  Future<void> stop();
}

/// Implementasi `dart:io` WebSocket: header `Authorization: Bearer`, ping tiap 25 detik, reconnect otomatis
/// dengan [Backoff]. Kode tutup 4401 / handshake 401 → [refreshToken] lalu sambung lagi.
class IoRealtimeClient implements RealtimeTransport {
  IoRealtimeClient({
    required this.url,
    required this.accessToken,
    required this.refreshToken,
    Backoff? backoff,
    this.pingInterval = const Duration(seconds: 25),
  }) : _backoff = backoff ?? Backoff();

  final Uri url;
  final Future<String?> Function() accessToken;

  /// Memaksa refresh access token (mis. memanggil API lewat interceptor). `false` = sesi habis.
  final Future<bool> Function() refreshToken;
  final Duration pingInterval;
  final Backoff _backoff;
  final _random = Random();

  final _frames = StreamController<Map<String, dynamic>>.broadcast();
  final _connection = StreamController<bool>.broadcast();
  WebSocket? _ws;
  Timer? _ping;
  Timer? _retry;
  var _running = false;
  var _pingSeq = 0;

  @override
  Stream<Map<String, dynamic>> get frames => _frames.stream;

  @override
  Stream<bool> get connection => _connection.stream;

  @override
  bool get isConnected => _ws != null;

  @override
  void start() {
    if (_running) return;
    _running = true;
    _connect();
  }

  Future<void> _connect() async {
    _retry = null;
    if (!_running) return;
    final token = await accessToken();
    if (token == null) {
      _running = false;
      return;
    }
    try {
      final ws = await WebSocket.connect(url.toString(), headers: {'Authorization': 'Bearer $token'})
          .timeout(const Duration(seconds: 10));
      if (!_running) {
        await ws.close();
        return;
      }
      _ws = ws;
      _backoff.reset();
      _connection.add(true);
      _ping = Timer.periodic(pingInterval, (_) => send('ping', const {}, id: 'p${_pingSeq++}'));
      ws.listen(
        (data) {
          if (data is String) {
            final decoded = jsonDecode(data);
            if (decoded is Map<String, dynamic>) _frames.add(decoded);
          }
        },
        onDone: () => _onClosed(ws.closeCode),
        onError: (_) => _onClosed(null),
        cancelOnError: true,
      );
    } on WebSocketException catch (e) {
      // Handshake 401: token kedaluwarsa.
      _onClosed(e.message.contains('401') ? 4401 : null);
    } catch (_) {
      _onClosed(null);
    }
  }

  Future<void> _onClosed(int? code) async {
    final wasConnected = _ws != null;
    _ws = null;
    _ping?.cancel();
    if (wasConnected) _connection.add(false);
    if (!_running) return;
    if (code == 4401) {
      final ok = await refreshToken();
      if (!ok) {
        _running = false;
        return;
      }
    }
    _retry?.cancel();
    _retry = Timer(code == closeServiceRestart ? restartDelay(_random) : _backoff.next(), _connect);
  }

  @override
  bool send(String type, Map<String, dynamic> data, {String? id}) {
    final ws = _ws;
    if (ws == null) return false;
    ws.add(jsonEncode({'t': type, 'id': ?id, 'd': data}));
    return true;
  }

  @override
  Future<void> stop() async {
    _running = false;
    _retry?.cancel();
    _ping?.cancel();
    final ws = _ws;
    _ws = null;
    await ws?.close();
  }
}
