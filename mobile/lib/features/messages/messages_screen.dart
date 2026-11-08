import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../profile/alias_avatar.dart';
import 'chat_models.dart';
import 'chat_providers.dart';
import 'chat_time.dart';

/// Tab Pesan (functional spec FS-05 §5.3): **Pesan** (percakapan) dan **Permintaan** (request masuk).
class MessagesScreen extends ConsumerWidget {
  const MessagesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final requests = ref.watch(chatBadgeProvider).value?.requests ?? 0;
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Pesan'),
          bottom: TabBar(tabs: [
            const Tab(text: 'Pesan'),
            Tab(child: Row(mainAxisSize: MainAxisSize.min, children: [
              const Text('Permintaan'),
              if (requests > 0) ...[
                const SizedBox(width: 6),
                Badge(label: Text('$requests'), key: const Key('requests-badge')),
              ],
            ])),
          ]),
        ),
        body: const TabBarView(children: [
          ConversationList(box: 'inbox'),
          ConversationList(box: 'requests'),
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
    return list.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => _Empty(
        icon: Icons.cloud_off,
        text: ApiError.from(e).message,
        action: TextButton(onPressed: () => ref.invalidate(conversationsProvider(box)), child: const Text('Coba lagi')),
      ),
      data: (items) {
        if (items.isEmpty) {
          return _Empty(
            icon: box == 'inbox' ? Icons.chat_bubble_outline : Icons.mark_email_unread_outlined,
            text: box == 'inbox'
                ? 'Belum ada bisikan. Tekan Bisik di sebuah post untuk memulai.'
                : 'Tidak ada permintaan baru.',
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
            child: ListView.separated(
              itemCount: items.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) => ConversationTile(conversation: items[i]),
            ),
          ),
        );
      },
    );
  }
}

class ConversationTile extends StatelessWidget {
  const ConversationTile({super.key, required this.conversation});

  final Conversation conversation;

  @override
  Widget build(BuildContext context) {
    final c = conversation;
    final theme = Theme.of(context);
    final at = c.lastMessageAt;
    return ListTile(
      key: Key('conversation-${c.id}'),
      leading: AliasAvatar(alias: c.peer.asAlias, radius: 22),
      title: Text(c.peer.name, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text(_preview(c), maxLines: 1, overflow: TextOverflow.ellipsis),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (at != null) Text(chatRelative(at, DateTime.now()), style: theme.textTheme.bodySmall),
          if (c.unreadCount > 0) ...[
            const SizedBox(height: 4),
            Badge(label: Text('${c.unreadCount}')),
          ],
        ],
      ),
      onTap: () => context.push('/pesan/${c.id}'),
    );
  }

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
    if (m.isImage) return m.mine ? 'Kamu: 📷 Gambar' : '📷 Gambar';
    if (m.isSystem) return 'Lawan bicara telah pergi';
    return m.mine ? 'Kamu: ${m.body ?? ''}' : (m.body ?? '');
  }
}

class _Empty extends StatelessWidget {
  const _Empty({required this.icon, required this.text, this.action});

  final IconData icon;
  final String text;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 48, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text(text, textAlign: TextAlign.center),
            ?action,
          ]),
        ),
      );
}
