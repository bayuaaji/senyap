import 'dart:typed_data';

import 'package:senyap/features/compose/background_picker.dart';
import 'package:senyap/features/posts/location_service.dart';
import 'package:senyap/features/posts/post_models.dart';
import 'package:senyap/features/posts/posts_repository.dart';

const authorA = Author(publicId: 'aaaa', name: 'Kelinci Jingga', tag: '4821', color: '#F28C38');
const authorB = Author(publicId: 'bbbb', name: 'Rubah Biru', tag: '1203', color: '#1E88E5');

Post samplePost({String id = '101', bool mine = false, DateTime? createdAt}) => Post(
      id: id,
      author: mine ? authorA : authorB,
      body: 'Aku belum bilang ke siapa-siapa kalau aku resign minggu depan.',
      category: 'kerja',
      city: 'Yogyakarta',
      heartCount: 3,
      replyCount: 1,
      isMine: mine,
      canWhisper: !mine,
      createdAt: createdAt ?? DateTime.now().toUtc().subtract(const Duration(minutes: 5)),
    );

/// Repo palsu di memori: post yang dibuat, balasan, hati, galeri. [requests] mencatat panggilan penting.
class FakePostsRepository implements PostsRepository {
  FakePostsRepository({List<Post>? posts, Map<String, List<Reply>>? replies})
      : posts = posts ?? [],
        repliesByPost = replies ?? {};

  final List<Post> posts;
  final Map<String, List<Reply>> repliesByPost;
  final hearts = <String, bool>{};
  final requests = <String>[];
  var _seq = 900;

  @override
  Future<PageOf<Post>> feed(FeedKind kind, {String? cursor, String? cell, String? category}) async {
    requests.add('${kind.name}${cell == null ? '' : ':$cell'}${category == null ? '' : ':$category'}');
    final items = kind == FeedKind.category ? posts.where((p) => p.category == category).toList() : posts;
    return PageOf(items, null);
  }

  @override
  Future<Post> post(String id) async => posts.firstWhere((p) => p.id == id);

  @override
  Future<Post> create({
    required String body,
    required String category,
    required String backgroundId,
    String? geohash,
  }) async {
    requests.add('create:$category:$backgroundId:${geohash ?? '-'}');
    final post = Post(
      id: '${_seq++}',
      author: authorA,
      body: body,
      category: category,
      city: geohash == null ? null : 'Yogyakarta',
      isMine: true,
      createdAt: DateTime.now().toUtc(),
    );
    posts.insert(0, post);
    return post;
  }

  @override
  Future<void> delete(String postId) async => posts.removeWhere((p) => p.id == postId);

  @override
  Future<void> setHeart(String postId, bool hearted) async => hearts[postId] = hearted;

  @override
  Future<PageOf<Reply>> replies(String postId, {String? cursor}) async =>
      PageOf(List.of(repliesByPost[postId] ?? const <Reply>[]), null);

  @override
  Future<Reply> reply(String postId, String body) async {
    final post = posts.firstWhere((p) => p.id == postId);
    final reply = Reply(
      id: '${_seq++}',
      postId: postId,
      author: Author(
          publicId: authorA.publicId, name: authorA.name, tag: authorA.tag, color: authorA.color,
          isPostAuthor: post.isMine),
      body: body,
      createdAt: DateTime.now().toUtc(),
      isMine: true,
    );
    repliesByPost.putIfAbsent(postId, () => []).add(reply);
    return reply;
  }

  @override
  Future<void> deleteReply(String replyId) async {
    for (final list in repliesByPost.values) {
      list.removeWhere((r) => r.id == replyId);
    }
  }

  @override
  Future<void> setReplyHidden(String replyId, bool hidden) async => requests.add('hide:$replyId:$hidden');

  @override
  Future<PageOf<GalleryItem>> backgrounds(String category, {String? cursor}) async => PageOf(
        [GalleryItem(id: 'bg-$category-1', url: 'http://localhost/none.jpg', blurhash: 'LEHV6nWB2yk8pyo0adR*.7kCMdnj')],
        null,
      );

  @override
  Future<GalleryItem> uploadBackground(Uint8List bytes, String mimeType) async {
    requests.add('upload:$mimeType');
    return const GalleryItem(id: 'uploaded-1', url: 'http://localhost/up.jpg');
  }
}

class FakeLocationService implements LocationService {
  FakeLocationService({this.cell = 'qqw7xs'});

  final String? cell;
  var asked = 0;

  @override
  Future<String?> currentCell() async {
    asked++;
    return cell;
  }
}

class FakeBackgroundPicker implements BackgroundPicker {
  @override
  Future<PickedImage?> pick() async => PickedImage(Uint8List.fromList([0xFF, 0xD8, 0xFF]), 'image/jpeg');
}
