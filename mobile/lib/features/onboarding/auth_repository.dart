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

/// Endpoint FS-01 yang menyangkut sesi. Nomor HP, email, dan kata sandi hanya dikirim ke server, tidak
/// disimpan di device.
abstract interface class AuthRepository {
  Future<void> requestOtp(String phone);
  Future<VerifyResult> verify({required String phone, required String code});

  /// Akun baru dengan email (sudah konfirmasi 18+ dan setuju syarat). Error: `EMAIL_TAKEN`, `WEAK_PASSWORD`,
  /// `INVALID_EMAIL`, `VALIDATION_FAILED`.
  Future<VerifyResult> registerEmail({required String email, required String password});

  /// Error: `INVALID_CREDENTIALS`.
  Future<VerifyResult> loginEmail({required String email, required String password});

  /// Mengirim kode reset 6 digit ke email (server selalu 202, juga untuk email yang tidak terdaftar).
  Future<void> forgotPassword(String email);
  Future<void> resetPassword({required String email, required String code, required String newPassword});
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
          'device': _device,
          'age_confirmed': true,
        });
        return _result(res.data!);
      });

  @override
  Future<VerifyResult> registerEmail({required String email, required String password}) => _call(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/auth/email/register', data: {
          'email': email,
          'password': password,
          'device': _device,
          'age_confirmed': true,
        });
        return _result(res.data!);
      });

  @override
  Future<VerifyResult> loginEmail({required String email, required String password}) => _call(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/auth/email/login', data: {
          'email': email,
          'password': password,
          'device': _device,
        });
        return _result(res.data!);
      });

  @override
  Future<void> forgotPassword(String email) =>
      _call(() => _dio.post<void>('/v1/auth/email/forgot', data: {'email': email}));

  @override
  Future<void> resetPassword({required String email, required String code, required String newPassword}) =>
      _call(() => _dio.post<void>('/v1/auth/email/reset',
          data: {'email': email, 'code': code, 'new_password': newPassword}));

  static Map<String, dynamic> get _device => {'platform': _platform, 'push_token': null};

  static VerifyResult _result(Map<String, dynamic> data) => VerifyResult(
        tokens: Tokens.fromJson(data['tokens'] as Map<String, dynamic>),
        isNew: data['is_new'] as bool? ?? false,
      );

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
