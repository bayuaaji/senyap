import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../../core/time/relative_time.dart';
import '../safety/safety_actions.dart';
import '../safety/safety_repository.dart';
import '../profile/alias_avatar.dart';
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
    final ok = await showConfirm(
      context,
      icon: Icons.delete_outline_rounded,
      title: 'Hapus cerita ini?',
      message: 'Cerita dan semua balasannya tidak akan terlihat lagi oleh siapa pun.',
      confirmLabel: 'Hapus',
      destructive: true,
    );
    if (!ok) return;
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
    showToast(context, ApiError.from(e).message, tone: ToastTone.error);
  }

  @override
  Widget build(BuildContext context) {
    final post = _post;
    final c = context.colors;
    return Scaffold(
      appBar: const SenyapBar(title: 'Cerita'),
      body: post == null
          ? Center(
              child: _error == null
                  ? const Loader()
                  : EmptyState(
                      icon: Icons.cloud_off_rounded,
                      title: 'Cerita tidak bisa dibuka',
                      message: _error!.message,
                    ),
            )
          : Column(children: [
              Expanded(
                child: RefreshIndicator(
                  onRefresh: _refresh,
                  child: ListView.builder(
                    padding: const EdgeInsets.only(top: Space.sm, bottom: Space.xl),
                    itemCount: 2 + _replies.length + (_repliesDone ? 0 : 1),
                    itemBuilder: (context, i) {
                      if (i == 0) {
                        return PostCard(
                          post: post,
                          heroTag: PostCard.heroTagFor(post.id),
                          onHeart: _toggleHeart,
                          onWhisper: () => openWhisper(context, type: WhisperOriginType.post, originId: post.id, excerpt: post.body),
                          onMore: post.isMine
                              ? _deletePost
                              : () => showSafetyMenu(context, ref, SafetyTarget.post, post.id,
                                  onBlocked: () => Navigator.of(context).pop(true)),
                        );
                      }
                      if (i == 1) {
                        final empty = _replies.isEmpty && _repliesDone;
                        return Padding(
                          padding: const EdgeInsets.fromLTRB(Space.gutter, Space.xxl, Space.gutter, Space.xs),
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Divider(color: c.line),
                            const SizedBox(height: Space.lg),
                            Text(empty ? 'Belum ada balasan.' : '${post.replyCount} balasan',
                                style: context.text.headlineSmall),
                            if (empty) ...[
                              const SizedBox(height: Space.xxs),
                              Text('Balasan pertama sering paling berarti untuk penulisnya.',
                                  style: context.text.bodyMedium),
                            ],
                          ]),
                        );
                      }
                      final idx = i - 2;
                      if (idx == _replies.length) {
                        WidgetsBinding.instance.addPostFrameCallback((_) {
                          if (mounted) _loadReplies();
                        });
                        return const Padding(padding: EdgeInsets.all(Space.lg), child: Loader());
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
              _ReplyComposer(controller: _input, sending: _sending, onSend: _send),
            ]),
    );
  }
}

/// Kolom balasan: menempel di bawah dengan garis rambut; input `raised` + tombol kirim persegi.
class _ReplyComposer extends StatelessWidget {
  const _ReplyComposer({required this.controller, required this.sending, required this.onSend});

  final TextEditingController controller;
  final bool sending;
  final VoidCallback onSend;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Container(
      decoration: BoxDecoration(
        color: c.bg,
        border: Border(top: BorderSide(color: c.line, width: 0.6)),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(Space.md, Space.sm, Space.md, Space.sm),
          child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
            Expanded(
              child: TextField(
                key: const Key('reply-input'),
                controller: controller,
                maxLength: 500,
                minLines: 1,
                maxLines: 4,
                textCapitalization: TextCapitalization.sentences,
                style: context.text.bodyLarge,
                decoration: const InputDecoration(
                  hintText: 'Tulis balasan…',
                  counterText: '',
                  isDense: true,
                  contentPadding: EdgeInsets.symmetric(horizontal: Space.md, vertical: 13),
                ),
              ),
            ),
            const SizedBox(width: Space.xs),
            ValueListenableBuilder(
              valueListenable: controller,
              builder: (context, value, _) {
                final ready = value.text.trim().isNotEmpty && !sending;
                return RoundIconButton(
                  key: const Key('reply-send'),
                  tooltip: 'Kirim balasan',
                  size: 46,
                  icon: Icons.arrow_upward_rounded,
                  color: ready ? c.text : c.raised,
                  iconColor: ready ? c.bg : c.textTertiary,
                  onPressed: sending ? null : onSend,
                );
              },
            ),
          ]),
        ),
      ),
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
    final c = context.colors;
    PopupMenuItem<String> item(String value, IconData icon, String label, {bool danger = false}) => PopupMenuItem(
          value: value,
          child: Row(children: [
            Icon(icon, size: 19, color: danger ? c.danger : c.textSecondary),
            const SizedBox(width: Space.sm),
            Text(label, style: TextStyle(color: danger ? c.danger : null)),
          ]),
        );
    final actions = <PopupMenuEntry<String>>[
      if (reply.isMine) item('delete', Icons.delete_outline_rounded, 'Hapus balasan', danger: true),
      if (viewerIsPostAuthor && !reply.isMine)
        reply.isHidden
            ? item('unhide', Icons.visibility_outlined, 'Tampilkan lagi')
            : item('hide', Icons.visibility_off_outlined, 'Sembunyikan'),
      if (!reply.isMine && reply.author.publicId != null) ...[
        item('report', Icons.flag_outlined, 'Laporkan'),
        item('block', Icons.block_rounded, 'Blokir', danger: true),
      ],
    ];
    return AnimatedOpacity(
      duration: Motion.base,
      opacity: reply.isHidden ? 0.45 : 1,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(Space.gutter, Space.lg, Space.xxs, Space.xs),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          AuthorAvatar(author: reply.author, radius: 16),
          const SizedBox(width: Space.sm),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Wrap(crossAxisAlignment: WrapCrossAlignment.center, spacing: 6, runSpacing: 4, children: [
                AliasName(text: reply.author.display, gender: reply.author.gender, style: context.text.titleSmall),
                if (reply.author.isPostAuthor)
                  Container(
                    key: const Key('author-badge'),
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(color: c.accentSoft, borderRadius: Radii.all(Radii.sm - 2)),
                    child: Text('Penulis',
                        style: context.text.labelSmall?.copyWith(color: c.accent, fontWeight: FontWeight.w700)),
                  ),
                if (reply.isHidden)
                  Text('· disembunyikan', style: context.text.bodySmall?.copyWith(color: c.textTertiary)),
              ]),
              const SizedBox(height: 2),
              RelativeTimeText(reply.createdAt,
                  clock: clock,
                  style: context.text.bodySmall?.copyWith(color: c.textTertiary, fontWeight: FontWeight.w400)),
              const SizedBox(height: Space.xs),
              Text(reply.body, style: PostType.reply.copyWith(color: c.text)),
              if (reply.canWhisper && onWhisper != null)
                // Bisik ke penulis balasan: tombol teks beraksen di bawah isi, sejajar teks.
                Transform.translate(
                  offset: const Offset(-Space.xs, 0),
                  child: TextButton.icon(
                    key: const Key('reply-whisper'),
                    style: TextButton.styleFrom(
                      foregroundColor: c.accent,
                      padding: const EdgeInsets.symmetric(horizontal: Space.xs),
                    ),
                    onPressed: onWhisper,
                    icon: const Icon(Icons.lock_outline_rounded, size: 16),
                    label: const Text('Bisik'),
                  ),
                )
              else
                const SizedBox(height: Space.xs),
            ]),
          ),
          if (actions.isNotEmpty && onAction != null)
            PopupMenuButton<String>(
              tooltip: 'Lainnya',
              icon: Icon(Icons.more_horiz_rounded, size: 20, color: c.textTertiary),
              onSelected: onAction,
              itemBuilder: (_) => actions,
            ),
        ]),
      ),
    );
  }
}
