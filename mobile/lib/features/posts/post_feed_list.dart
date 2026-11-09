import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/shell.dart' show navClearance;
import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../safety/safety_actions.dart';
import '../safety/safety_repository.dart';
import '../whisper/whisper_entry.dart';
import 'post_card.dart';
import 'post_models.dart';
import 'posts_repository.dart';

/// Daftar post dengan paginasi cursor, tarik untuk muat ulang, dan aksi kartu (hati, Bisik, buka balasan).
class PostFeedList extends ConsumerStatefulWidget {
  const PostFeedList({
    super.key,
    required this.load,
    required this.emptyMessage,
    this.emptyTitle = 'Masih sunyi di sini',
    this.topPadding = Space.lg,
  });

  final Future<PageOf<Post>> Function(PostsRepository repo, String? cursor) load;
  final String emptyMessage;
  final String emptyTitle;
  final double topPadding;

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
      showToast(context, ApiError.from(e).message, tone: ToastTone.error);
    }
  }

  Future<void> _open(Post post) async {
    final deleted = await context.push<bool>('/post/${post.id}', extra: post);
    if (deleted == true && mounted) setState(() => _posts.removeWhere((p) => p.id == post.id));
  }

  /// Membagi indeks post ke dua kolom: tiap post masuk ke kolom yang perkiraan tingginya lebih pendek
  /// (masonry). Dihitung ulang tiap build supaya tetap benar saat halaman baru masuk atau post dihapus.
  (List<int>, List<int>) _columns(double tileWidth) {
    final left = <int>[], right = <int>[];
    var hl = 0.0, hr = 0.0;
    for (var i = 0; i < _posts.length; i++) {
      final h = PostTile.estimateHeight(_posts[i], tileWidth) + Space.xl;
      if (hl <= hr) {
        left.add(i);
        hl += h;
      } else {
        right.add(i);
        hr += h;
      }
    }
    return (left, right);
  }

  Widget _tile(int i) {
    final post = _posts[i];
    return PostTile(
      key: ValueKey(post.id),
      post: post,
      heroTag: PostCard.heroTagFor(post.id),
      onTap: () => _open(post),
      onHeart: () => _toggleHeart(i),
      onWhisper: () => openWhisper(context, type: WhisperOriginType.post, originId: post.id, excerpt: post.body),
      onMore: post.isMine || post.author.publicId == null
          ? null
          : () => showSafetyMenu(context, ref, SafetyTarget.post, post.id,
              onBlocked: () => setState(() => _posts.removeWhere((p) => p.author.publicId == post.author.publicId))),
    );
  }

  /// Gutter layar dan jarak antarkolom (DESIGN.md: skala spasi).
  static const _side = Space.md;
  static const _between = Space.sm;

  SliverList _column(List<int> indices) => SliverList.separated(
        itemCount: indices.length,
        itemBuilder: (context, k) => _tile(indices[k]),
        separatorBuilder: (_, _) => const SizedBox(height: Space.xl),
      );

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final bottom = navClearance(context);
    if (_posts.isEmpty) {
      if (_loading) {
        return Shimmer(
          child: ListView(
            physics: const NeverScrollableScrollPhysics(),
            padding: EdgeInsets.fromLTRB(_side, widget.topPadding, _side, bottom),
            children: const [
              Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(child: Column(children: [PostTileSkeleton(aspect: 0.8), SizedBox(height: Space.xl), PostTileSkeleton(aspect: 1)])),
                SizedBox(width: _between),
                Expanded(child: Column(children: [PostTileSkeleton(aspect: 1), SizedBox(height: Space.xl), PostTileSkeleton(aspect: 0.66)])),
              ]),
            ],
          ),
        );
      }
      return RefreshIndicator(
        onRefresh: refresh,
        child: ListView(padding: EdgeInsets.only(bottom: bottom), children: [
          const SizedBox(height: Space.md),
          _error == null
              ? EmptyState(title: widget.emptyTitle, message: widget.emptyMessage)
              : EmptyState(
                  icon: Icons.cloud_off_rounded,
                  title: 'Belum tersambung',
                  message: _error!.message,
                  action: SecondaryButton(label: 'Coba lagi', expand: false, onPressed: refresh),
                ),
        ]),
      );
    }
    return LayoutBuilder(builder: (context, box) {
      final tileWidth = (box.maxWidth - _side * 2 - _between) / 2;
      final (left, right) = _columns(tileWidth);
      return RefreshIndicator(
        onRefresh: refresh,
        child: NotificationListener<ScrollNotification>(
          onNotification: (n) {
            if (n.metrics.pixels > n.metrics.maxScrollExtent - 600) _loadMore();
            return false;
          },
          child: CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              SliverPadding(
                padding: EdgeInsets.fromLTRB(_side, widget.topPadding, _side, 0),
                sliver: SliverCrossAxisGroup(slivers: [
                  SliverCrossAxisExpanded(flex: 1, sliver: _column(left)),
                  const SliverConstrainedCrossAxis(maxExtent: _between, sliver: SliverToBoxAdapter(child: SizedBox.shrink())),
                  SliverCrossAxisExpanded(flex: 1, sliver: _column(right)),
                ]),
              ),
              SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.fromLTRB(Space.md, Space.md, Space.md, bottom),
                  child: _done
                      ? const SizedBox.shrink()
                      : _error != null
                          ? Center(child: TextButton(onPressed: _loadMore, child: const Text('Coba lagi')))
                          : const Loader(),
                ),
              ),
            ],
          ),
        ),
      );
    });
  }
}
