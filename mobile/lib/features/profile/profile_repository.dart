import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import 'profile_models.dart';

/// `GET/PUT /v1/me/profile`, `POST /v1/me/alias/suggest`, `PUT /v1/me/alias`, `GET /v1/aliases/catalog`.
abstract interface class ProfileRepository {
  Future<Profile> profile();

  /// Saran alias sesuai gender user. [first]/[second] kosong = diacak server.
  Future<AliasSuggestion> suggest({String? first, String? second});
  Future<Profile> useAlias(String suggestionToken);
  Future<AliasCatalog> catalog(Gender gender);
  Future<Profile> setGender(Gender gender);

  /// [avatarId] `null` = hapus avatar (kembali ke inisial).
  Future<Profile> setAvatar(String? avatarId);
}

final profileRepositoryProvider =
    Provider<ProfileRepository>((ref) => DioProfileRepository(ref.watch(dioProvider)));

class DioProfileRepository implements ProfileRepository {
  DioProfileRepository(this._dio);

  final Dio _dio;

  @override
  Future<Profile> profile() => _call(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/me/profile');
        return Profile.fromJson(res.data!);
      });

  @override
  Future<AliasSuggestion> suggest({String? first, String? second}) => _call(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/me/alias/suggest', data: {
          'first': ?first,
          'second': ?second,
        });
        return AliasSuggestion.fromJson(res.data!);
      });

  @override
  Future<Profile> useAlias(String suggestionToken) => _call(() async {
        final res = await _dio.put<Map<String, dynamic>>('/v1/me/alias',
            data: {'suggestion_token': suggestionToken});
        return Profile.fromJson(res.data!);
      });

  @override
  Future<AliasCatalog> catalog(Gender gender) => _call(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/aliases/catalog',
            queryParameters: {'gender': gender.wire});
        return AliasCatalog.fromJson(res.data!);
      });

  @override
  Future<Profile> setGender(Gender gender) => _put({'gender': gender.wire});

  @override
  Future<Profile> setAvatar(String? avatarId) => _put({'avatar': avatarId});

  Future<Profile> _put(Map<String, dynamic> body) => _call(() async {
        final res = await _dio.put<Map<String, dynamic>>('/v1/me/profile', data: body);
        return Profile.fromJson(res.data!);
      });

  Future<T> _call<T>(Future<T> Function() body) async {
    try {
      return await body();
    } catch (e) {
      throw ApiError.from(e);
    }
  }
}
