import 'dart:async';

import 'package:dio/dio.dart';

import '../auth/token_store.dart';

/// Menambahkan `Authorization: Bearer`, dan saat API menjawab 401 mencoba sekali `POST /v1/auth/refresh`
/// (refresh token dirotasi), lalu mengulang request. Beberapa 401 bersamaan hanya memicu satu refresh.
/// Kalau refresh gagal, token dihapus dan [onSessionExpired] dipanggil.
class AuthInterceptor extends QueuedInterceptor {
  AuthInterceptor({
    required this.tokens,
    required this.refreshDio,
    required this.retryDio,
    required this.onSessionExpired,
  });

  final TokenStore tokens;

  /// Dio tanpa interceptor ini, untuk memanggil endpoint refresh.
  final Dio refreshDio;

  /// Dio tanpa interceptor ini untuk mengulang request (header Authorization diisi di sini).
  final Dio retryDio;

  final void Function() onSessionExpired;

  static const _retriedKey = 'senyap.retried';
  static const publicPaths = {'/v1/auth/otp', '/v1/auth/verify', '/v1/auth/refresh'};

  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    if (!publicPaths.contains(options.path)) {
      final current = await tokens.read();
      if (current != null) {
        options.headers['Authorization'] = 'Bearer ${current.accessToken}';
      }
    }
    handler.next(options);
  }

  @override
  Future<void> onError(DioException err, ErrorInterceptorHandler handler) async {
    final request = err.requestOptions;
    if (err.response?.statusCode != 401 ||
        publicPaths.contains(request.path) ||
        request.extra[_retriedKey] == true) {
      return handler.next(err);
    }
    final current = await tokens.read();
    if (current == null) {
      onSessionExpired();
      return handler.next(err);
    }
    // Request lain mungkin sudah me-refresh saat request ini mengantre.
    final sentWith = request.headers['Authorization'];
    var fresh = current;
    if (sentWith == 'Bearer ${current.accessToken}') {
      try {
        final res = await refreshDio.post<Map<String, dynamic>>(
          '/v1/auth/refresh',
          data: {'refresh_token': current.refreshToken},
        );
        fresh = Tokens.fromJson(res.data!['tokens'] as Map<String, dynamic>);
        await tokens.save(fresh);
      } on DioException catch (e) {
        if (e.response?.statusCode == 401) {
          await tokens.clear();
          onSessionExpired();
        }
        return handler.next(err);
      }
    }
    try {
      request.extra[_retriedKey] = true;
      request.headers['Authorization'] = 'Bearer ${fresh.accessToken}';
      handler.resolve(await retryDio.fetch<dynamic>(request));
    } on DioException catch (e) {
      handler.next(e);
    }
  }
}
