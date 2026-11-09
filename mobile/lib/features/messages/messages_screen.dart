import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/shell.dart' show navClearance;
import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../profile/alias_avatar.dart';
import 'chat_models.dart';
import 'chat_providers.dart';
import 'chat_time.dart';

/// Tab Pesan (functional spec FS-05 §5.3): **Pesan** (percakapan) dan **Permintaan** (request masuk).
class MessagesScreen extends ConsumerStatefulWidget {
  const MessagesScreen({super.key});

  @override
  ConsumerState<MessagesScreen> createState() => _MessagesScreenState();
}

class _MessagesScreenState extends ConsumerState<MessagesScreen> with SingleTickerProviderStateMixin {
  late final _tabs = TabController(length: 2, vsync: this);

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final requests = ref.watch(chatBadgeProvider).value?.requests ?? 0;
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const ScreenTitle('Pesan',
              subtitle: 'Obrolan berdua. Hanya alias yang terlihat.',
              padding: EdgeInsets.fromLTRB(Space.gutter, Space.lg, Space.gutter, Space.md)),
          TextTabs(
            controller: _tabs,
            tabs: [
              const TabItem('Pesan'),
              TabItem('Permintaan', badge: requests, badgeKey: const Key('requests-badge')),
            ],
          ),
          Expanded(
            child: TabBarView(controller: _tabs, children: const [
              ConversationList(box: 'inbox'),
              ConversationList(box: 'requests'),
            ]),
          ),
        ]),
      ),
    );
  }
}

class ConversationList extends ConsumerWidget {
  const ConversationList({super.key, required this.box});

  final String box;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final list = ref.watch(conversationsProvider(box));
    final bottom = navClearance(context);
    return list.when(
      loading: () => const ListSkeleton(),
      error: (e, _) => _Scrollable(
        child: EmptyState(
          icon: Icons.cloud_off_rounded,
          title: 'Belum tersambung',
          message: ApiError.from(e).message,
          action: SecondaryButton(
            label: 'Coba lagi',
            expand: false,
            onPressed: () => ref.invalidate(conversationsProvider(box)),
          ),
        ),
      ),
      data: (items) {
        if (items.isEmpty) {
          return RefreshIndicator(
            onRefresh: () => ref.refresh(conversationsProvider(box).future),
            child: _Scrollable(
              child: box == 'inbox'
                  ? const EmptyState(
                      title: 'Belum ada bisikan',
                      message: 'Tekan Bisik di sebuah cerita untuk memulai obrolan berdua. Kalian tetap tanpa nama.',
                    )
                  : const EmptyState(
                      title: 'Tidak ada permintaan baru',
                      message: 'Saat seseorang ingin berbisik denganmu, permintaannya muncul di sini dulu.',
                    ),
            ),
          );
        }
        final notifier = ref.read(conversationsProvider(box).notifier);
        return RefreshIndicator(
          onRefresh: () => ref.refresh(conversationsProvider(box).future),
          child: NotificationListener<ScrollNotification>(
            onNotification: (n) {
              if (n.metrics.extentAfter < 300 && notifier.hasMore) notifier.loadMore();
              return false;
            },
            child: ListView.builder(
              padding: EdgeInsets.only(top: Space.xs, bottom: bottom),
              itemCount: items.length,
              itemBuilder: (context, i) => ConversationTile(conversation: items[i]),
            ),
          ),
        );
      },
    );
  }
}

class _Scrollable extends StatelessWidget {
  const _Scrollable({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => ListView(
        padding: EdgeInsets.only(top: Space.md, bottom: navClearance(context)),
        children: [child],
      );
}

class ConversationTile extends StatelessWidget {
  const ConversationTile({super.key, required this.conversation});

  final Conversation conversation;

  @override
  Widget build(BuildContext context) {
    final c = conversation;
    final colors = context.colors;
    final at = c.lastMessageAt;
    final unread = c.unreadCount > 0;
    final status = _isStatusLine(c);
    return Material(
      type: MaterialType.transparency,
      child: InkWell(
        key: Key('conversation-${c.id}'),
        onTap: () => context.push('/pesan/${c.id}'),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: Space.gutter, vertical: Space.md),
          child: Row(children: [
            Hero(
              tag: 'chat-avatar:${c.id}',
              child: AliasAvatar(alias: c.peer.asAlias, emoji: c.peer.avatar, radius: 24),
            ),
            const SizedBox(width: Space.md),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Expanded(
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: AliasName(
                        text: c.peer.name,
                        gender: c.peer.gender,
                        style: context.text.titleMedium?.copyWith(fontWeight: unread ? FontWeight.w700 : FontWeight.w600),
                      ),
                    ),
                  ),
                  if (at != null) ...[
                    const SizedBox(width: Space.xs),
                    Text(chatRelative(at, DateTime.now()),
                        style: context.text.bodySmall?.copyWith(
                          color: unread ? colors.accent : colors.textTertiary,
                          fontWeight: unread ? FontWeight.w600 : FontWeight.w400,
                        )),
                  ],
                ]),
                const SizedBox(height: 3),
                Row(children: [
                  if (status) ...[
                    Icon(_statusIcon(c), size: 14, color: colors.textTertiary),
                    const SizedBox(width: 4),
                  ],
                  Expanded(
                    child: Text(
                      _preview(c),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: context.text.bodyMedium?.copyWith(
                        color: status ? colors.textTertiary : (unread ? colors.text : colors.textSecondary),
                        fontStyle: status ? FontStyle.italic : FontStyle.normal,
                        fontWeight: unread ? FontWeight.w500 : FontWeight.w400,
                      ),
                    ),
                  ),
                  if (unread) ...[
                    const SizedBox(width: Space.xs),
                    Container(
                      constraints: const BoxConstraints(minWidth: 20),
                      height: 20,
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      alignment: Alignment.center,
                      decoration: BoxDecoration(color: colors.accent, borderRadius: Radii.all(10)),
                      child: Text('${c.unreadCount}',
                          style: context.text.labelSmall?.copyWith(color: colors.onAccent, fontWeight: FontWeight.w700)),
                    ),
                  ],
                ]),
              ]),
            ),
          ]),
        ),
      ),
    );
  }

  static bool _isStatusLine(Conversation c) =>
      c.status == ConversationStatus.declined ||
      c.status == ConversationStatus.closed ||
      (c.status == ConversationStatus.requested && c.isInitiator);

  static IconData _statusIcon(Conversation c) => switch (c.status) {
        ConversationStatus.declined => Icons.do_not_disturb_on_outlined,
        ConversationStatus.closed => Icons.logout_rounded,
        _ => Icons.hourglass_empty_rounded,
      };

  static String _preview(Conversation c) {
    switch (c.status) {
      case ConversationStatus.declined:
        return 'Permintaan tidak diterima';
      case ConversationStatus.requested when c.isInitiator:
        return 'Menunggu diterima';
      case ConversationStatus.closed:
        return 'Lawan bicara telah pergi';
      default:
        break;
    }
    final m = c.lastMessage;
    if (m == null) return c.excerpt ?? '';
    if (m.retracted) return 'Pesan ditarik';
    if (m.isImage) return m.mine ? 'Kamu: Foto' : 'Foto';
    if (m.isSystem) return 'Lawan bicara telah pergi';
    return m.mine ? 'Kamu: ${m.body ?? ''}' : (m.body ?? '');
  }
}
