import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/api/auth_interceptor.dart';
import 'package:senyap/core/auth/token_store.dart';

/// Server palsu: access token valid hanya [validAccess]; refresh merotasi token.
class FakeServer implements HttpClientAdapter {
  String validAccess = 'akses-1';
  String validRefresh = 'segar-1';
  bool refreshFails = false;
  int refreshCalls = 0;
  final authHeaders = <String?>[];

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    if (options.path == '/v1/auth/refresh') {
      refreshCalls++;
      final body = jsonDecode(await utf8.decodeStream(requestStream!)) as Map;
      await Future<void>.delayed(const Duration(milliseconds: 10));
      if (refreshFails || body['refresh_token'] != validRefresh) {
        return _json(401, {'error': {'code': 'UNAUTHENTICATED', 'message': 'x', 'request_id': 'r'}});
      }
      validAccess = 'akses-${refreshCalls + 1}';
      validRefresh = 'segar-${refreshCalls + 1}';
      return _json(200, {'tokens': {'access_token': validAccess, 'refresh_token': validRefresh, 'expires_in': 900}});
    }
    final auth = options.headers['Authorization'] as String?;
    authHeaders.add(auth);
    if (auth != 'Bearer $validAccess') {
      return _json(401, {'error': {'code': 'UNAUTHENTICATED', 'message': 'x', 'request_id': 'r'}});
    }
    return _json(200, {'ok': true});
  }

  static ResponseBody _json(int status, Object body) => ResponseBody.fromString(jsonEncode(body), status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});

  @override
  void close({bool force = false}) {}
}

void main() {
  late FakeServer server;
  late InMemoryTokenStore tokens;
  late Dio dio;
  late int expired;

  setUp(() {
    server = FakeServer();
    tokens = InMemoryTokenStore(const Tokens(accessToken: 'akses-lama', refreshToken: 'segar-1'));
    expired = 0;
    Dio make() => Dio(BaseOptions(baseUrl: 'http://api.test'))..httpClientAdapter = server;
    final plain = make();
    dio = make()
      ..interceptors.add(AuthInterceptor(
        tokens: tokens,
        refreshDio: plain,
        retryDio: plain,
        onSessionExpired: () => expired++,
      ));
  });

  test('401 → refresh sekali → request diulang dengan token baru', () async {
    final res = await dio.get<Map<String, dynamic>>('/v1/me/profile');
    expect(res.data, {'ok': true});
    expect(server.refreshCalls, 1);
    expect((await tokens.read())!.refreshToken, 'segar-2');
    expect(server.authHeaders, ['Bearer akses-lama', 'Bearer akses-2']);
  });

  test('beberapa 401 bersamaan hanya memicu satu refresh', () async {
    final results = await Future.wait([
      dio.get<dynamic>('/v1/me/profile'),
      dio.get<dynamic>('/v1/aliases/catalog'),
      dio.get<dynamic>('/v1/me/profile'),
    ]);
    expect(results.map((r) => r.statusCode), [200, 200, 200]);
    expect(server.refreshCalls, 1);
    expect(expired, 0);
  });

  test('refresh ditolak → token dihapus dan sesi berakhir', () async {
    server.refreshFails = true;
    await expectLater(dio.get<dynamic>('/v1/me/profile'), throwsA(isA<DioException>()));
    expect(await tokens.read(), isNull);
    expect(expired, 1);
  });

  test('endpoint login tidak membawa Authorization', () async {
    // Server palsu menolak tanpa token; yang diuji: header tidak dikirim dan tidak ada refresh.
    await expectLater(dio.post<dynamic>('/v1/auth/otp', data: {'phone': '0812'}), throwsA(isA<DioException>()));
    expect(server.authHeaders.single, isNull);
    expect(server.refreshCalls, 0);
  });
}
