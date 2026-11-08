import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import 'post_models.dart';

/// Post, feed, balasan, hati, galeri latar, upload latar (functional spec FS-02, FS-03, FS-04).
abstract interface class PostsRepository {
  /// [cell] wajib untuk [FeedKind.nearby]; [category] wajib untuk [FeedKind.category].
  Future<PageOf<Post>> feed(FeedKind kind, {String? cursor, String? cell, String? category});
  Future<Post> post(String id);
  Future<Post> create({
    required String body,
    required String category,
    required String backgroundId,
    String? geohash,
  });
  Future<void> delete(String postId);
  Future<void> setHeart(String postId, bool hearted);
  Future<PageOf<Reply>> replies(String postId, {String? cursor});
  Future<Reply> reply(String postId, String body);
  Future<void> deleteReply(String replyId);
  Future<void> setReplyHidden(String replyId, bool hidden);
  Future<PageOf<GalleryItem>> backgrounds(String category, {String? cursor});

  /// Upload latar sendiri: presigned PUT → complete → tunggu worker. Mengembalikan media siap pakai.
  Future<GalleryItem> uploadBackground(Uint8List bytes, String mimeType);
}

final postsRepositoryProvider = Provider<PostsRepository>((ref) => DioPostsRepository(ref.watch(dioProvider)));

class DioPostsRepository implements PostsRepository {
  DioPostsRepository(this._dio, {Dio? storage, this.pollInterval = const Duration(seconds: 1)})
      : _storage = storage ?? Dio();

  final Dio _dio;

  /// Tanpa interceptor auth: presigned URL tidak boleh membawa access token.
  final Dio _storage;
  final Duration pollInterval;

  @override
  Future<PageOf<Post>> feed(FeedKind kind, {String? cursor, String? cell, String? category}) => _call(() async {
        final path = switch (kind) {
          FeedKind.latest => '/v1/feed/latest',
          FeedKind.popular => '/v1/feed/popular',
          FeedKind.nearby => '/v1/feed/nearby',
          FeedKind.category => '/v1/feed/category/$category',
        };
        final res = await _dio.get<Map<String, dynamic>>(path, queryParameters: {
          'cursor': ?cursor,
          if (kind == FeedKind.nearby) 'cell': cell,
        });
        return PageOf.fromJson(res.data!, Post.fromJson);
      });

  @override
  Future<Post> post(String id) => _call(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/posts/$id');
        return Post.fromJson(res.data!);
      });

  @override
  Future<Post> create({
    required String body,
    required String category,
    required String backgroundId,
    String? geohash,
  }) =>
      _call(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/posts', data: {
          'body': body,
          'category': category,
          'bg_media_id': backgroundId,
          'use_location': geohash != null,
          'geohash': ?geohash,
        });
        return Post.fromJson(res.data!);
      });

  @override
  Future<void> delete(String postId) => _call(() => _dio.delete<void>('/v1/posts/$postId'));

  @override
  Future<void> setHeart(String postId, bool hearted) => _call(() =>
      hearted ? _dio.put<void>('/v1/posts/$postId/heart') : _dio.delete<void>('/v1/posts/$postId/heart'));

  @override
  Future<PageOf<Reply>> replies(String postId, {String? cursor}) => _call(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/posts/$postId/replies',
            queryParameters: {'cursor': ?cursor});
        return PageOf.fromJson(res.data!, Reply.fromJson);
      });

  @override
  Future<Reply> reply(String postId, String body) => _call(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/posts/$postId/replies', data: {'body': body});
        return Reply.fromJson(res.data!);
      });

  @override
  Future<void> deleteReply(String replyId) => _call(() => _dio.delete<void>('/v1/replies/$replyId'));

  @override
  Future<void> setReplyHidden(String replyId, bool hidden) => _call(() => hidden
      ? _dio.put<void>('/v1/replies/$replyId/hidden')
      : _dio.delete<void>('/v1/replies/$replyId/hidden'));

  @override
  Future<PageOf<GalleryItem>> backgrounds(String category, {String? cursor}) => _call(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/backgrounds',
            queryParameters: {'category': category, 'cursor': ?cursor});
        return PageOf.fromJson(res.data!, GalleryItem.fromJson);
      });

  @override
  Future<GalleryItem> uploadBackground(Uint8List bytes, String mimeType) => _call(() async {
        final ticket = (await _dio.post<Map<String, dynamic>>('/v1/media/uploads',
                data: {'mime_type': mimeType, 'size_bytes': bytes.length}))
            .data!;
        final headers = Map<String, dynamic>.from(ticket['upload_headers'] as Map)
          ..removeWhere((k, _) => k.toLowerCase() == 'content-length');
        await _storage.put<void>(
          ticket['upload_url'] as String,
          data: Stream.fromIterable([bytes]),
          options: Options(headers: {...headers, Headers.contentLengthHeader: bytes.length}),
        );
        final id = ticket['media_id'] as String;
        var status = (await _dio.post<Map<String, dynamic>>('/v1/media/$id/complete')).data!;
        for (var i = 0; i < 60 && status['status'] == 'PROCESSING'; i++) {
          await Future<void>.delayed(pollInterval);
          status = (await _dio.get<Map<String, dynamic>>('/v1/media/$id')).data!;
        }
        if (status['status'] != 'READY') {
          throw const ApiError(code: 'MEDIA_REJECTED', message: 'Gambar ini tidak bisa dipakai. Coba gambar lain.');
        }
        final bg = status['background'] as Map<String, dynamic>;
        return GalleryItem(id: id, url: bg['url'] as String, blurhash: bg['blurhash'] as String?);
      });

  Future<T> _call<T>(Future<T> Function() body) async {
    try {
      return await body();
    } catch (e) {
      throw ApiError.from(e);
    }
  }
}
