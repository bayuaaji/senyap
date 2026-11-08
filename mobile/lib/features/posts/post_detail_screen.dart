import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_error.dart';
import '../../core/time/relative_time.dart';
import '../safety/safety_actions.dart';
import '../safety/safety_repository.dart';
import '../whisper/whisper_entry.dart';
import 'post_card.dart';
import 'post_models.dart';
import 'posts_repository.dart';

/// Post + balasan (functional spec FS-04): balasan terlama → terbaru, alias penulis, lencana Penulis,
/// hapus balasan sendiri, pembuat post bisa menyembunyikan balasan. Pop dengan `true` bila post dihapus.
class PostDetailScreen extends ConsumerStatefulWidget {
  const PostDetailScreen({super.key, required this.postId, this.initial});

  final String postId;
  final Post? initial;

  @override
  ConsumerState<PostDetailScreen> createState() => _PostDetailScreenState();
}

class _PostDetailScreenState extends ConsumerState<PostDetailScreen> {
  Post? _post;
  ApiError? _error;
  final _replies = <Reply>[];
  String? _cursor;
  bool _loadingReplies = false;
  bool _repliesDone = false;
  final _input = TextEditingController();
  bool _sending = false;

  PostsRepository get _repo => ref.read(postsRepositoryProvider);

  @override
  void initState() {
    super.initState();
    _post = widget.initial;
    _refresh();
  }

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final post = await _repo.post(widget.postId);
      if (mounted) setState(() => _post = post);
    } catch (e) {
      if (mounted) setState(() => _error = ApiError.from(e));
      return;
    }
    _replies.clear();
    _cursor = null;
    _repliesDone = false;
    await _loadReplies();
  }

  Future<void> _loadReplies() async {
    if (_loadingReplies || _repliesDone) return;
    setState(() => _loadingReplies = true);
    try {
      final page = await _repo.replies(widget.postId, cursor: _cursor);
      if (!mounted) return;
      setState(() {
        _replies.addAll(page.items);
        _cursor = page.nextCursor;
        _repliesDone = page.nextCursor == null;
      });
    } catch (e) {
      _toast(e);
    } finally {
      if (mounted) setState(() => _loadingReplies = false);
    }
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty || _sending) return;
    setState(() => _sending = true);
    try {
      final reply = await _repo.reply(widget.postId, text);
      if (!mounted) return;
      _input.clear();
      if (reply.supportCard) showSupportCard(context, ref);
      setState(() {
        if (_repliesDone) _replies.add(reply);
        final p = _post;
        if (p != null) _post = p.copyWith(replyCount: p.replyCount + 1);
      });
    } catch (e) {
      _toast(e);
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  Future<void> _toggleHeart() async {
    final before = _post;
    if (before == null) return;
    setState(() => _post = before.withHeart(!before.viewerHasHearted));
    try {
      await _repo.setHeart(before.id, !before.viewerHasHearted);
    } catch (e) {
      if (mounted) setState(() => _post = before);
      _toast(e);
    }
  }

  Future<void> _deletePost() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Hapus post?'),
        content: const Text('Post dan balasannya tidak akan terlihat lagi.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Batal')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Hapus')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await _repo.delete(widget.postId);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      _toast(e);
    }
  }

  Future<void> _replyAction(Reply reply, String action) async {
    try {
      switch (action) {
        case 'delete':
          await _repo.deleteReply(reply.id);
          if (mounted) setState(() => _replies.removeWhere((r) => r.id == reply.id));
        case 'hide':
        case 'unhide':
          await _repo.setReplyHidden(reply.id, action == 'hide');
          await _refresh();
        case 'report':
          await showReportSheet(context, ref, SafetyTarget.reply, reply.id, onBlocked: _refresh);
        case 'block':
          await confirmBlock(context, ref, SafetyTarget.reply, reply.id, onBlocked: _refresh);
      }
    } catch (e) {
      _toast(e);
    }
  }

  void _toast(Object e) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(ApiError.from(e).message)));
  }

  @override
  Widget build(BuildContext context) {
    final post = _post;
    return Scaffold(
      appBar: AppBar(title: const Text('Balasan')),
      body: post == null
          ? Center(child: _error == null ? const CircularProgressIndicator() : Text(_error!.message))
          : Column(children: [
              Expanded(
                child: RefreshIndicator(
                  onRefresh: _refresh,
                  child: ListView.builder(
                    itemCount: 2 + _replies.length + (_repliesDone ? 0 : 1),
                    itemBuilder: (context, i) {
                      if (i == 0) {
                        return PostCard(
                          post: post,
                          onHeart: _toggleHeart,
                          onWhisper: () => openWhisper(context, type: WhisperOriginType.post, originId: post.id, excerpt: post.body),
                          onMore: post.isMine
                              ? _deletePost
                              : () => showSafetyMenu(context, ref, SafetyTarget.post, post.id,
                                  onBlocked: () => Navigator.of(context).pop(true)),
                        );
                      }
                      if (i == 1) {
                        return Padding(
                          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
                          child: Text(
                            _replies.isEmpty && _repliesDone ? 'Belum ada balasan.' : '${post.replyCount} balasan',
                            style: Theme.of(context).textTheme.titleSmall,
                          ),
                        );
                      }
                      final idx = i - 2;
                      if (idx == _replies.length) {
                        WidgetsBinding.instance.addPostFrameCallback((_) {
                          if (mounted) _loadReplies();
                        });
                        return const Padding(
                          padding: EdgeInsets.all(16),
                          child: Center(child: CircularProgressIndicator()),
                        );
                      }
                      final reply = _replies[idx];
                      return ReplyTile(
                        reply: reply,
                        viewerIsPostAuthor: post.isMine,
                        onWhisper: () => openWhisper(context, type: WhisperOriginType.reply, originId: reply.id, excerpt: reply.body),
                        onAction: (a) => _replyAction(reply, a),
                      );
                    },
                  ),
                ),
              ),
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(12, 4, 4, 8),
                  child: Row(children: [
                    Expanded(
                      child: TextField(
                        key: const Key('reply-input'),
                        controller: _input,
                        maxLength: 500,
                        minLines: 1,
                        maxLines: 4,
                        decoration: const InputDecoration(hintText: 'Tulis balasan…', counterText: ''),
                      ),
                    ),
                    IconButton(
                      key: const Key('reply-send'),
                      tooltip: 'Kirim',
                      onPressed: _sending ? null : _send,
                      icon: const Icon(Icons.send),
                    ),
                  ]),
                ),
              ),
            ]),
    );
  }
}

class ReplyTile extends StatelessWidget {
  const ReplyTile({
    super.key,
    required this.reply,
    required this.viewerIsPostAuthor,
    this.onWhisper,
    this.onAction,
    this.clock = DateTime.now,
  });

  final Reply reply;
  final bool viewerIsPostAuthor;
  final VoidCallback? onWhisper;
  final ValueChanged<String>? onAction;
  final DateTime Function() clock;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final actions = <PopupMenuEntry<String>>[
      if (reply.isMine) const PopupMenuItem(value: 'delete', child: Text('Hapus balasan')),
      if (viewerIsPostAuthor && !reply.isMine)
        reply.isHidden
            ? const PopupMenuItem(value: 'unhide', child: Text('Tampilkan lagi'))
            : const PopupMenuItem(value: 'hide', child: Text('Sembunyikan')),
      if (!reply.isMine && reply.author.publicId != null) ...[
        const PopupMenuItem(value: 'report', child: Text('Laporkan')),
        const PopupMenuItem(value: 'block', child: Text('Blokir')),
      ],
    ];
    return Opacity(
      opacity: reply.isHidden ? 0.5 : 1,
      child: ListTile(
        leading: AuthorAvatar(author: reply.author, radius: 18),
        title: Wrap(crossAxisAlignment: WrapCrossAlignment.center, spacing: 6, children: [
          Text(reply.author.display, style: const TextStyle(fontWeight: FontWeight.w600)),
          if (reply.author.isPostAuthor)
            Container(
              key: const Key('author-badge'),
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
              decoration: BoxDecoration(
                color: theme.colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text('Penulis', style: TextStyle(fontSize: 11, color: theme.colorScheme.onPrimaryContainer)),
            ),
          if (reply.isHidden) const Text('· disembunyikan', style: TextStyle(fontSize: 12)),
        ]),
        subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const SizedBox(height: 2),
          Text(reply.body, style: theme.textTheme.bodyMedium),
          const SizedBox(height: 4),
          RelativeTimeText(reply.createdAt, clock: clock, style: theme.textTheme.bodySmall),
        ]),
        trailing: Row(mainAxisSize: MainAxisSize.min, children: [
          if (reply.canWhisper && onWhisper != null)
            IconButton(key: const Key('reply-whisper'), tooltip: 'Bisik', onPressed: onWhisper, icon: const Icon(Icons.lock_outline)),
          if (actions.isNotEmpty && onAction != null)
            PopupMenuButton<String>(onSelected: onAction, itemBuilder: (_) => actions),
        ]),
      ),
    );
  }
}
