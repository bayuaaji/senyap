import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../auth/token_store.dart';
import '../config.dart';
import 'auth_interceptor.dart';

final tokenStoreProvider = Provider<TokenStore>((ref) => SecureTokenStore());

/// Dipanggil interceptor saat refresh token ditolak; [SessionController] mendengarkannya.
final sessionExpiredProvider = NotifierProvider<SessionExpiredSignal, int>(SessionExpiredSignal.new);

class SessionExpiredSignal extends Notifier<int> {
  @override
  int build() => 0;

  void fire() => state++;
}

BaseOptions _options() => BaseOptions(
      baseUrl: AppConfig.apiBaseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 20),
      contentType: Headers.jsonContentType,
      responseType: ResponseType.json,
    );

/// Dio utama aplikasi, sudah dengan interceptor auth.
final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(_options());
  // Tanpa interceptor: refresh + mengulang request. Mengulang lewat `dio` sendiri bisa deadlock karena
  // antrean onError QueuedInterceptor masih memegang request yang sedang ditangani.
  final plain = Dio(_options());
  dio.interceptors.add(AuthInterceptor(
    tokens: ref.watch(tokenStoreProvider),
    refreshDio: plain,
    retryDio: plain,
    onSessionExpired: () => ref.read(sessionExpiredProvider.notifier).fire(),
  ));
  ref.onDispose(() {
    dio.close();
    plain.close();
  });
  return dio;
});
