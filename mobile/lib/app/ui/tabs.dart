import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme/theme.dart';

/// Satu tab teks: label + angka opsional (mis. jumlah permintaan).
class TabItem {
  const TabItem(this.label, {this.badge = 0, this.badgeKey});

  final String label;
  final int badge;
  final Key? badgeKey;
}

/// Tab berupa kata rata kiri yang mengikuti [TabController]. Aktif: tebal, warna teks, garis jambu 2 px
/// di bawahnya. Bisa digeser horizontal bila tidak muat.
class TextTabs extends StatefulWidget {
  const TextTabs({super.key, required this.controller, required this.tabs, this.padding});

  final TabController controller;
  final List<TabItem> tabs;
  final EdgeInsetsGeometry? padding;

  @override
  State<TextTabs> createState() => _TextTabsState();
}

class _TextTabsState extends State<TextTabs> {
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_changed);
  }

  @override
  void didUpdateWidget(TextTabs old) {
    super.didUpdateWidget(old);
    if (old.controller != widget.controller) {
      old.controller.removeListener(_changed);
      widget.controller.addListener(_changed);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_changed);
    super.dispose();
  }

  void _changed() => setState(() {});

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final selected = widget.controller.index;
    Widget tab(int i) {
      final t = widget.tabs[i];
      final active = i == selected;
      return Semantics(
        selected: active,
        button: true,
        child: MouseRegion(
          cursor: SystemMouseCursors.click,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () {
              if (i != selected) HapticFeedback.selectionClick();
              widget.controller.animateTo(i, duration: Motion.base, curve: Motion.curve);
            },
            child: Padding(
              padding: const EdgeInsets.only(right: Space.xl),
              child: IntrinsicWidth(
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  const Spacer(),
                  Row(mainAxisSize: MainAxisSize.min, children: [
                    AnimatedDefaultTextStyle(
                      duration: Motion.fast,
                      style: context.text.titleMedium!.copyWith(
                        color: active ? c.text : c.textTertiary,
                        fontWeight: active ? FontWeight.w700 : FontWeight.w600,
                      ),
                      child: Text(t.label, maxLines: 1, softWrap: false),
                    ),
                    if (t.badge > 0) ...[
                      const SizedBox(width: 6),
                      Text(
                        '${t.badge}',
                        key: t.badgeKey,
                        style: context.text.labelMedium?.copyWith(color: c.accent, fontWeight: FontWeight.w700),
                      ),
                    ],
                  ]),
                  const SizedBox(height: Space.xs),
                  AnimatedContainer(
                    duration: Motion.base,
                    curve: Motion.curve,
                    height: 2,
                    decoration: BoxDecoration(
                      color: active ? c.accent : Colors.transparent,
                      borderRadius: Radii.all(1),
                    ),
                  ),
                ]),
              ),
            ),
          ),
        ),
      );
    }

    return Container(
      height: 48,
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.line, width: 0.6))),
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: widget.padding ?? const EdgeInsets.only(left: Space.gutter, right: Space.xs),
        children: [for (var i = 0; i < widget.tabs.length; i++) tab(i)],
      ),
    );
  }
}

/// Pilihan tunggal berbentuk chip persegi membulat (kategori). Aktif: isi tinta, teks `bg`.
class ChoiceChips<T> extends StatelessWidget {
  const ChoiceChips({super.key, required this.options, required this.selected, required this.onSelected, this.padding});

  final Map<T, String> options;
  final T selected;
  final ValueChanged<T> onSelected;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final entries = options.entries.toList();
    return SizedBox(
      height: 44,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: padding ?? Space.screen,
        itemCount: entries.length,
        separatorBuilder: (_, _) => const SizedBox(width: Space.xs),
        itemBuilder: (context, i) {
          final e = entries[i];
          final active = e.key == selected;
          return Semantics(
            selected: active,
            button: true,
            child: MouseRegion(
              cursor: SystemMouseCursors.click,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () {
                  if (!active) HapticFeedback.selectionClick();
                  onSelected(e.key);
                },
                child: Center(
                  child: AnimatedContainer(
                    duration: Motion.fast,
                    curve: Motion.curve,
                    height: 36,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: active ? c.text : c.raised,
                      borderRadius: Radii.all(Radii.md),
                    ),
                    child: Text(e.value,
                        style: context.text.labelMedium?.copyWith(color: active ? c.bg : c.textSecondary)),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
