import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../whisper/whisper_entry.dart';
import 'post_card.dart';
import 'post_models.dart';
import 'posts_repository.dart';

/// Daftar post dengan paginasi cursor, tarik untuk muat ulang, dan aksi kartu (hati, Bisik, buka balasan).
class PostFeedList extends ConsumerStatefulWidget {
  const PostFeedList({super.key, required this.load, required this.emptyMessage});

  final Future<PageOf<Post>> Function(PostsRepository repo, String? cursor) load;
  final String emptyMessage;

  @override
  ConsumerState<PostFeedList> createState() => _PostFeedListState();
}

class _PostFeedListState extends ConsumerState<PostFeedList> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  final _posts = <Post>[];
  String? _cursor;
  bool _loading = false;
  bool _done = false;
  ApiError? _error;

  @override
  void initState() {
    super.initState();
    _loadMore();
  }

  Future<void> refresh() async {
    setState(() {
      _posts.clear();
      _cursor = null;
      _done = false;
      _error = null;
    });
    await _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loading || _done) return;
    setState(() => _loading = true);
    try {
      final page = await widget.load(ref.read(postsRepositoryProvider), _cursor);
      if (!mounted) return;
      setState(() {
        _posts.addAll(page.items);
        _cursor = page.nextCursor;
        _done = page.nextCursor == null;
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = ApiError.from(e));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _toggleHeart(int index) async {
    final before = _posts[index];
    setState(() => _posts[index] = before.withHeart(!before.viewerHasHearted));
    try {
      await ref.read(postsRepositoryProvider).setHeart(before.id, !before.viewerHasHearted);
    } catch (e) {
      if (!mounted) return;
      setState(() => _posts[index] = before);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(ApiError.from(e).message)));
    }
  }

  Future<void> _open(Post post) async {
    final deleted = await context.push<bool>('/post/${post.id}', extra: post);
    if (deleted == true && mounted) setState(() => _posts.removeWhere((p) => p.id == post.id));
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    if (_posts.isEmpty) {
      if (_loading) return const Center(child: CircularProgressIndicator());
      return RefreshIndicator(
        onRefresh: refresh,
        child: ListView(children: [
          const SizedBox(height: 120),
          Icon(_error == null ? Icons.dynamic_feed : Icons.cloud_off, size: 48, color: Colors.grey),
          const SizedBox(height: 12),
          Center(child: Text(_error?.message ?? widget.emptyMessage, textAlign: TextAlign.center)),
        ]),
      );
    }
    return RefreshIndicator(
      onRefresh: refresh,
      child: NotificationListener<ScrollNotification>(
        onNotification: (n) {
          if (n.metrics.pixels > n.metrics.maxScrollExtent - 600) _loadMore();
          return false;
        },
        child: ListView.builder(
          padding: const EdgeInsets.only(bottom: 24),
          itemCount: _posts.length + (_done ? 0 : 1),
          itemBuilder: (context, i) {
            if (i == _posts.length) {
              return Padding(
                padding: const EdgeInsets.all(16),
                child: Center(
                  child: _error != null
                      ? TextButton(onPressed: _loadMore, child: const Text('Coba lagi'))
                      : const CircularProgressIndicator(),
                ),
              );
            }
            final post = _posts[i];
            return PostCard(
              key: ValueKey(post.id),
              post: post,
              onTap: () => _open(post),
              onHeart: () => _toggleHeart(i),
              onWhisper: () => openWhisper(context, type: WhisperOriginType.post, originId: post.id, excerpt: post.body),
            );
          },
        ),
      ),
    );
  }
}
