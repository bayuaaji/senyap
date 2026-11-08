import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import 'profile_models.dart';

/// `GET /v1/me/profile`, `POST /v1/me/alias/suggest`, `PUT /v1/me/alias`, `GET /v1/aliases/catalog`.
abstract interface class ProfileRepository {
  Future<Profile> profile();
  Future<AliasSuggestion> suggest({String? animal, String? color});
  Future<Profile> useAlias(String suggestionToken);
  Future<AliasCatalog> catalog();
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
  Future<AliasSuggestion> suggest({String? animal, String? color}) => _call(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/me/alias/suggest', data: {
          'animal': ?animal,
          'color': ?color,
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
  Future<AliasCatalog> catalog() => _call(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/aliases/catalog');
        return AliasCatalog.fromJson(res.data!);
      });

  Future<T> _call<T>(Future<T> Function() body) async {
    try {
      return await body();
    } catch (e) {
      throw ApiError.from(e);
    }
  }
}
