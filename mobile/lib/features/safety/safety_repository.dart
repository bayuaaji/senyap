import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';

/// Jenis konten untuk report/block (FS-07). Server menentukan akun di baliknya.
enum SafetyTarget { post, reply, message, conversation }

extension SafetyTargetWire on SafetyTarget {
  String get wire => name.toUpperCase();
}

/// Alasan report, sesuai functional spec FS-07.
const reportReasons = <String, String>{
  'harassment': 'Pelecehan atau perundungan',
  'threat': 'Ancaman kekerasan',
  'sexual': 'Konten seksual',
  'minor_safety': 'Membahayakan anak',
  'self_harm': 'Risiko menyakiti diri',
  'doxxing': 'Menyebar data pribadi',
  'spam': 'Spam atau penipuan',
  'hate': 'Ujaran kebencian',
  'other': 'Lainnya',
};

class SupportCard {
  const SupportCard({required this.title, required this.message, required this.verified, required this.hotlines});

  final String title;
  final String message;

  /// `false` = nomor belum dicek manusia; app menandainya "perlu dicek sebelum rilis".
  final bool verified;
  final List<Hotline> hotlines;

  factory SupportCard.fromJson(Map<String, dynamic> j) => SupportCard(
        title: j['title'] as String,
        message: j['message'] as String,
        verified: j['verified'] as bool? ?? false,
        hotlines: [
          for (final h in (j['hotlines'] as List? ?? const []))
            Hotline(
              name: (h as Map<String, dynamic>)['name'] as String,
              contact: h['contact'] as String,
              hours: h['hours'] as String?,
            ),
        ],
      );
}

class Hotline {
  const Hotline({required this.name, required this.contact, this.hours});
  final String name;
  final String contact;
  final String? hours;
}

class BlockedAccount {
  const BlockedAccount({required this.id, required this.name, this.tag, required this.createdAt});
  final String id;
  final String name;
  final String? tag;
  final DateTime createdAt;

  String get display => tag == null ? name : '$name #$tag';
}

abstract interface class SafetyRepository {
  Future<void> report(SafetyTarget type, String targetId, String reason, {int? seq, String? note});
  Future<void> block(SafetyTarget type, String targetId, {int? seq});
  Future<List<BlockedAccount>> blocks();
  Future<void> unblock(String blockId);
  Future<SupportCard> supportCard();
}

final safetyRepositoryProvider = Provider<SafetyRepository>((ref) => DioSafetyRepository(ref.watch(dioProvider)));

class DioSafetyRepository implements SafetyRepository {
  DioSafetyRepository(this._dio);

  final Dio _dio;

  @override
  Future<void> report(SafetyTarget type, String targetId, String reason, {int? seq, String? note}) =>
      _call(() => _dio.post<dynamic>('/v1/reports', data: {
            'target_type': type.wire,
            'target_id': targetId,
            'seq': ?seq,
            'reason': reason,
            if (note != null && note.trim().isNotEmpty) 'note': note.trim(),
          }));

  @override
  Future<void> block(SafetyTarget type, String targetId, {int? seq}) => _call(() => _dio.post<dynamic>('/v1/blocks',
      data: {'target_type': type.wire, 'target_id': targetId, 'seq': ?seq}));

  @override
  Future<List<BlockedAccount>> blocks() => _call(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/me/blocks');
        return [
          for (final j in (res.data!['items'] as List))
            BlockedAccount(
              id: (j as Map<String, dynamic>)['id'] as String,
              name: j['name'] as String,
              tag: j['tag'] as String?,
              createdAt: DateTime.parse(j['created_at'] as String),
            ),
        ];
      });

  @override
  Future<void> unblock(String blockId) => _call(() => _dio.delete<void>('/v1/me/blocks/$blockId'));

  @override
  Future<SupportCard> supportCard() => _call(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/support/self-harm');
        return SupportCard.fromJson(res.data!);
      });

  Future<T> _call<T>(Future<T> Function() body) async {
    try {
      return await body();
    } catch (e) {
      throw ApiError.from(e);
    }
  }
}
