import 'package:dio/dio.dart';

/// Error dari API dengan format `{ "error": { code, message, request_id } }` (functional spec §4).
class ApiError implements Exception {
  const ApiError({
    required this.code,
    required this.message,
    this.status,
    this.retryAfter,
  });

  final String code;
  final String message;
  final int? status;
  final Duration? retryAfter;

  static const _offline = ApiError(
    code: 'NETWORK',
    message: 'Tidak bisa terhubung. Periksa koneksi internetmu.',
  );

  factory ApiError.from(Object error) {
    if (error is ApiError) return error;
    if (error is DioException) {
      final response = error.response;
      if (response == null) return _offline;
      final data = response.data;
      final retry = int.tryParse(response.headers.value('retry-after') ?? '');
      if (data is Map && data['error'] is Map) {
        final body = data['error'] as Map;
        return ApiError(
          code: body['code'] as String? ?? 'INTERNAL',
          message: body['message'] as String? ?? 'Terjadi kesalahan.',
          status: response.statusCode,
          retryAfter: retry == null ? null : Duration(seconds: retry),
        );
      }
      return ApiError(
        code: 'INTERNAL',
        message: 'Terjadi kesalahan. Coba lagi nanti.',
        status: response.statusCode,
      );
    }
    return const ApiError(code: 'INTERNAL', message: 'Terjadi kesalahan. Coba lagi nanti.');
  }

  @override
  String toString() => 'ApiError($code)';
}
