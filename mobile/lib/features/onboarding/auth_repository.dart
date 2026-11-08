import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/auth/token_store.dart';

class VerifyResult {
  const VerifyResult({required this.tokens, required this.isNew});

  final Tokens tokens;
  final bool isNew;
}

/// Endpoint FS-01 yang menyangkut sesi. Nomor HP hanya dikirim ke server, tidak disimpan di device.
abstract interface class AuthRepository {
  Future<void> requestOtp(String phone);
  Future<VerifyResult> verify({required String phone, required String code});
  Future<void> logout();
  Future<void> deleteAccount();
}

final authRepositoryProvider =
    Provider<AuthRepository>((ref) => DioAuthRepository(ref.watch(dioProvider)));

class DioAuthRepository implements AuthRepository {
  DioAuthRepository(this._dio);

  final Dio _dio;

  static String get _platform => defaultTargetPlatform == TargetPlatform.iOS ? 'ios' : 'android';

  @override
  Future<void> requestOtp(String phone) => _call(() => _dio.post<void>('/v1/auth/otp', data: {'phone': phone}));

  @override
  Future<VerifyResult> verify({required String phone, required String code}) => _call(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/auth/verify', data: {
          'phone': phone,
          'code': code,
          // Push token didaftarkan lewat PUT /v1/me/device setelah FCM/APNs disiapkan (Fase 2).
          'device': {'platform': _platform, 'push_token': null},
          'age_confirmed': true,
        });
        return VerifyResult(
          tokens: Tokens.fromJson(res.data!['tokens'] as Map<String, dynamic>),
          isNew: res.data!['is_new'] as bool,
        );
      });

  @override
  Future<void> logout() => _call(() => _dio.post<void>('/v1/auth/logout'));

  @override
  Future<void> deleteAccount() => _call(() => _dio.delete<void>('/v1/me', data: {'confirm': 'HAPUS'}));

  Future<T> _call<T>(Future<T> Function() body) async {
    try {
      return await body();
    } catch (e) {
      throw ApiError.from(e);
    }
  }
}
