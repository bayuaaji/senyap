import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import 'chat_models.dart';

/// Hasil kirim (REST atau `msg.ack`): pesan sudah tersimpan di server dengan `seq`.
class SendAck {
  const SendAck({required this.clientMsgId, required this.conversationId, required this.seq, required this.createdAt});

  final String clientMsgId;
  final String conversationId;
  final int seq;
  final DateTime createdAt;

  factory SendAck.fromJson(Map<String, dynamic> j) => SendAck(
        clientMsgId: j['client_msg_id'] as String,
        conversationId: j['conversation_id'] as String,
        seq: (j['seq'] as num).toInt(),
        createdAt: DateTime.parse(j['created_at'] as String),
      );
}

class ConversationPage {
  const ConversationPage(this.items, this.nextCursor);

  final List<Conversation> items;
  final String? nextCursor;
}

/// REST Bisik (functional spec FS-05 §5.4). Semua error dilempar sebagai [ApiError].
abstract interface class ChatRepository {
  Future<ConversationPage> list({required String box, String? cursor});
  Future<Conversation> detail(String id);
  Future<Conversation> start({
    required String originType,
    required String originId,
    required String firstMessage,
    required String clientMsgId,
  });
  Future<Conversation> accept(String id);
  Future<void> decline(String id);
  Future<void> leave(String id);
  Future<void> block(String id);
  Future<void> hide(String id);
  Future<List<ChatMessage>> messages(String id, {int? beforeSeq, int? afterSeq, int limit = 50});
  Future<SendAck> send(String id, {required String clientMsgId, required String type, String? body, String? mediaId});
  Future<void> retract(String id, int seq);
  Future<void> receipts(String id, {int? deliveredUpTo, int? readUpTo});

  /// Percakapan yang `last_seq`-nya lebih baru dari [known] (`conversation_id` → `last_seq`).
  Future<Map<String, int>> sync(Map<String, int> known);
  Future<ChatBadge> badge();

  /// Upload gambar DM (presigned PUT), mengembalikan `media_id`.
  Future<String> uploadImage(Uint8List bytes, String mimeType);
}

final chatRepositoryProvider = Provider<ChatRepository>((ref) => DioChatRepository(ref.watch(dioProvider)));

class DioChatRepository implements ChatRepository {
  DioChatRepository(this._dio);

  final Dio _dio;

  Future<T> _call<T>(Future<T> Function() f) async {
    try {
      return await f();
    } catch (e) {
      throw ApiError.from(e);
    }
  }

  Map<String, dynamic> _map(Response<dynamic> r) => r.data as Map<String, dynamic>;

  @override
  Future<ConversationPage> list({required String box, String? cursor}) => _call(() async {
        final r = await _dio.get<dynamic>('/v1/conversations',
            queryParameters: {'box': box, 'cursor': ?cursor});
        final m = _map(r);
        return ConversationPage(
          (m['items'] as List).map((e) => Conversation.fromJson(e as Map<String, dynamic>)).toList(),
          m['next_cursor'] as String?,
        );
      });

  @override
  Future<Conversation> detail(String id) =>
      _call(() async => Conversation.fromJson(_map(await _dio.get<dynamic>('/v1/conversations/$id'))));

  @override
  Future<Conversation> start({
    required String originType,
    required String originId,
    required String firstMessage,
    required String clientMsgId,
  }) =>
      _call(() async => Conversation.fromJson(_map(await _dio.post<dynamic>('/v1/conversations', data: {
            'origin_type': originType,
            'origin_id': originId,
            'first_message': firstMessage,
            'client_msg_id': clientMsgId,
          }))));

  @override
  Future<Conversation> accept(String id) =>
      _call(() async => Conversation.fromJson(_map(await _dio.post<dynamic>('/v1/conversations/$id/accept'))));

  @override
  Future<void> decline(String id) => _call(() => _dio.post<dynamic>('/v1/conversations/$id/decline'));

  @override
  Future<void> leave(String id) => _call(() => _dio.post<dynamic>('/v1/conversations/$id/leave'));

  @override
  Future<void> block(String id) => _call(() => _dio.post<dynamic>('/v1/conversations/$id/block'));

  @override
  Future<void> hide(String id) => _call(() => _dio.post<dynamic>('/v1/conversations/$id/hide'));

  @override
  Future<List<ChatMessage>> messages(String id, {int? beforeSeq, int? afterSeq, int limit = 50}) =>
      _call(() async {
        final r = await _dio.get<dynamic>('/v1/conversations/$id/messages',
            queryParameters: {'before_seq': ?beforeSeq, 'after_seq': ?afterSeq, 'limit': limit});
        return (_map(r)['items'] as List).map((e) => ChatMessage.fromJson(e as Map<String, dynamic>)).toList();
      });

  @override
  Future<SendAck> send(String id, {required String clientMsgId, required String type, String? body, String? mediaId}) =>
      _call(() async => SendAck.fromJson(_map(await _dio.post<dynamic>('/v1/conversations/$id/messages', data: {
            'client_msg_id': clientMsgId,
            'type': type,
            'body': ?body,
            'media_id': ?mediaId,
          }))));

  @override
  Future<void> retract(String id, int seq) => _call(() => _dio.delete<dynamic>('/v1/conversations/$id/messages/$seq'));

  @override
  Future<void> receipts(String id, {int? deliveredUpTo, int? readUpTo}) => _call(() => _dio.post<dynamic>(
      '/v1/conversations/$id/receipts',
      data: {'delivered_up_to': ?deliveredUpTo, 'read_up_to': ?readUpTo}));

  @override
  Future<Map<String, int>> sync(Map<String, int> known) => _call(() async {
        final r = await _dio.post<dynamic>('/v1/conversations/sync', data: {
          'conversations': [
            for (final e in known.entries) {'conversation_id': e.key, 'last_seq': e.value},
          ],
        });
        return {
          for (final c in _map(r)['changed'] as List)
            (c as Map)['conversation_id'] as String: (c['last_seq'] as num).toInt(),
        };
      });

  @override
  Future<ChatBadge> badge() => _call(() async {
        final m = _map(await _dio.get<dynamic>('/v1/conversations/badge'));
        return ChatBadge(
          unreadMessages: (m['unread_messages'] as num).toInt(),
          requests: (m['requests'] as num).toInt(),
        );
      });

  @override
  Future<String> uploadImage(Uint8List bytes, String mimeType) => _call(() async {
        final up = _map(await _dio.post<dynamic>('/v1/media/dm-uploads',
            data: {'mime_type': mimeType, 'size_bytes': bytes.length}));
        // Unggah langsung ke storage: tanpa header Authorization aplikasi.
        await Dio().put<dynamic>(
          up['upload_url'] as String,
          data: Stream.fromIterable([bytes]),
          options: Options(headers: {
            ...(up['upload_headers'] as Map).cast<String, String>(),
            Headers.contentLengthHeader: bytes.length,
          }),
        );
        final id = up['media_id'] as String;
        await _dio.post<dynamic>('/v1/media/dm-uploads/$id/complete');
        return id;
      });
}
