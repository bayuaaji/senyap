import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/messages/chat_providers.dart';
import 'ui/ui.dart';

class _Dest {
  const _Dest(this.key, this.label, this.icon, this.activeIcon);

  final String key;
  final String label;
  final IconData icon;
  final IconData activeIcon;
}

const _dests = [
  _Dest('nav-feed', 'Feed', Icons.view_agenda_outlined, Icons.view_agenda_rounded),
  _Dest('nav-compose', 'Tulis', Icons.edit_outlined, Icons.edit_rounded),
  _Dest('nav-messages', 'Pesan', Icons.chat_bubble_outline_rounded, Icons.chat_bubble_rounded),
  _Dest('nav-notifications', 'Notifikasi', Icons.notifications_none_rounded, Icons.notifications_rounded),
  _Dest('nav-settings', 'Pengaturan', Icons.person_outline_rounded, Icons.person_rounded),
];

/// Kerangka 5 tab: Feed, Tulis, Pesan, Notifikasi, Pengaturan. Nav bawah menempel (latar solid, garis
/// rambut di atas), disembunyikan di layar chat supaya kolom tulis punya ruang.
class AppShell extends ConsumerWidget {
  const AppShell({super.key, required this.shell, this.location = ''});

  final StatefulNavigationShell shell;
  final String location;

  /// Tinggi bar tanpa safe area.
  static const barHeight = 58.0;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Disembunyikan di chat, dan saat keyboard terbuka supaya nav tidak menumpang di atas keyboard.
    final hidden = location.startsWith('/pesan/') || MediaQuery.viewInsetsOf(context).bottom > 0;
    final unread = ref.watch(chatBadgeProvider).value;
    final dot = (unread?.unreadMessages ?? 0) + (unread?.requests ?? 0) > 0;
    return Scaffold(
      body: shell,
      bottomNavigationBar: hidden
          ? null
          : _BottomNav(
              index: shell.currentIndex,
              messagesDot: dot,
              onSelect: (i) {
                HapticFeedback.selectionClick();
                shell.goBranch(i, initialLocation: i == shell.currentIndex);
              },
            ),
    );
  }
}

class _BottomNav extends StatelessWidget {
  const _BottomNav({required this.index, required this.onSelect, required this.messagesDot});

  final int index;
  final ValueChanged<int> onSelect;
  final bool messagesDot;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return DecoratedBox(
      key: const Key('app-nav'),
      decoration: BoxDecoration(
        color: c.bg,
        border: Border(top: BorderSide(color: c.line, width: 0.6)),
      ),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: AppShell.barHeight,
          child: Row(children: [
            for (var i = 0; i < _dests.length; i++)
              Expanded(
                child: _NavItem(
                  dest: _dests[i],
                  active: i == index,
                  dot: i == 2 && messagesDot,
                  onTap: () => onSelect(i),
                ),
              ),
          ]),
        ),
      ),
    );
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({required this.dest, required this.active, required this.onTap, this.dot = false});

  final _Dest dest;
  final bool active;
  final bool dot;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final color = active ? c.text : c.textTertiary;
    return Semantics(
      selected: active,
      button: true,
      label: dest.label,
      excludeSemantics: true,
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        child: GestureDetector(
          key: Key(dest.key),
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            SizedBox(
              height: 26,
              child: Stack(clipBehavior: Clip.none, alignment: Alignment.center, children: [
                Icon(active ? dest.activeIcon : dest.icon, size: 24, color: color),
                if (dot)
                  Positioned(
                    top: 0,
                    right: -4,
                    child: Container(
                      width: 8,
                      height: 8,
                      decoration: BoxDecoration(
                        color: c.accent,
                        shape: BoxShape.circle,
                        border: Border.all(color: c.bg, width: 1.5),
                      ),
                    ),
                  ),
              ]),
            ),
            const SizedBox(height: 3),
            Text(
              dest.label,
              maxLines: 1,
              overflow: TextOverflow.fade,
              softWrap: false,
              style: context.text.labelSmall!.copyWith(
                fontSize: 11,
                letterSpacing: 0,
                color: color,
                fontWeight: active ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ]),
        ),
      ),
    );
  }
}

/// Jarak bawah untuk konten tab yang di-scroll. Nav sekarang menempel (bukan melayang), jadi cukup
/// ruang napas biasa.
double navClearance(BuildContext context) => Space.xxl;
