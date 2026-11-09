import 'package:flutter/material.dart';

import '../theme/theme.dart';
import 'buttons.dart';

/// App bar untuk layar yang di-push: tombol kembali, judul kecil opsional, aksi di kanan.
/// Latar solid `bg` (tanpa kaca/blur).
class SenyapBar extends StatelessWidget implements PreferredSizeWidget {
  const SenyapBar({
    super.key,
    this.title,
    this.titleWidget,
    this.actions = const [],
    this.showBack = true,
    this.onBack,
    this.transparent = false,
  });

  final String? title;
  final Widget? titleWidget;
  final List<Widget> actions;
  final bool showBack;
  final VoidCallback? onBack;

  /// Tanpa latar (dipakai saat layar di belakangnya sudah `bg`).
  final bool transparent;

  static const height = 56.0;

  @override
  Size get preferredSize => const Size.fromHeight(height);

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final canPop = showBack && (onBack != null || Navigator.of(context).canPop());
    final bar = SafeArea(
      bottom: false,
      child: SizedBox(
        height: height,
        child: Padding(
          padding: const EdgeInsets.only(left: Space.xs, right: Space.sm),
          child: Row(
            children: [
              if (canPop) ...[
                BackCircle(onPressed: onBack),
                const SizedBox(width: Space.xxs),
              ] else
                const SizedBox(width: Space.md),
              Expanded(
                child:
                    titleWidget ??
                    (title == null
                        ? const SizedBox.shrink()
                        : Text(title!, style: context.text.titleMedium, maxLines: 1, overflow: TextOverflow.ellipsis)),
              ),
              for (final a in actions)
                Padding(
                  padding: const EdgeInsets.only(left: Space.xxs),
                  child: a,
                ),
            ],
          ),
        ),
      ),
    );
    return transparent ? bar : ColoredBox(color: c.bg, child: bar);
  }
}

/// Judul layar serif rata kiri (tab utama dan formulir), dengan subjudul opsional.
class ScreenTitle extends StatelessWidget {
  const ScreenTitle(this.title, {super.key, this.subtitle, this.trailing, this.padding});

  final String title;
  final String? subtitle;
  final Widget? trailing;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: padding ?? const EdgeInsets.fromLTRB(Space.gutter, Space.lg, Space.gutter, Space.xl),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Expanded(child: Text(title, style: context.text.headlineLarge)),
              ?trailing,
            ],
          ),
          if (subtitle != null) ...[
            const SizedBox(height: Space.xs),
            Text(subtitle!, style: context.text.bodyMedium),
          ],
        ],
      ),
    );
  }
}

/// Label bagian: huruf biasa, kecil, tebal, warna sekunder.
class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key, this.padding, this.trailing});

  final String text;
  final EdgeInsetsGeometry? padding;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
    padding: padding ?? const EdgeInsets.fromLTRB(Space.gutter, Space.section, Space.gutter, Space.xs),
    child: Row(
      children: [
        Expanded(
          child: Semantics(
            header: true,
            child: Text(text, style: context.text.labelMedium?.copyWith(color: context.colors.textTertiary)),
          ),
        ),
        ?trailing,
      ],
    ),
  );
}

/// Kelompok baris datar di atas `bg`, dipisah garis rambut yang menjorok (tanpa kotak kartu).
class GroupCard extends StatelessWidget {
  const GroupCard({super.key, required this.children, this.margin});

  final List<Widget> children;
  final EdgeInsetsGeometry? margin;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Padding(
      padding: margin ?? EdgeInsets.zero,
      child: Column(
        children: [
          for (var i = 0; i < children.length; i++) ...[
            if (i > 0)
              Padding(
                // Menjorok sejajar teks baris (gutter + ikon 22 + jarak 16).
                padding: const EdgeInsets.only(left: Space.gutter + 38),
                child: Divider(color: c.line),
              ),
            children[i],
          ],
        ],
      ),
    );
  }
}

/// Baris di dalam [GroupCard]: ikon polos, judul, subjudul, chevron. Tinggi minimal 56.
class GroupTile extends StatelessWidget {
  const GroupTile({
    super.key,
    this.icon,
    required this.title,
    this.subtitle,
    this.onTap,
    this.danger = false,
    this.trailing,
    this.showChevron = true,
  });

  final IconData? icon;
  final String title;
  final String? subtitle;
  final VoidCallback? onTap;
  final bool danger;
  final Widget? trailing;
  final bool showChevron;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final tint = danger ? c.danger : c.textSecondary;
    return Material(
      type: MaterialType.transparency,
      child: InkWell(
        onTap: onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: Space.row),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(Space.gutter, Space.sm, Space.md, Space.sm),
            child: Row(
              children: [
                if (icon != null) ...[
                  Icon(icon, size: 22, color: onTap == null ? c.textTertiary : tint),
                  const SizedBox(width: Space.md),
                ],
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        title,
                        style: context.text.bodyLarge?.copyWith(
                          fontWeight: FontWeight.w500,
                          color: danger ? c.danger : (onTap == null && trailing == null ? c.textSecondary : c.text),
                        ),
                      ),
                      if (subtitle != null) ...[
                        const SizedBox(height: 2),
                        Text(subtitle!, style: context.text.bodySmall?.copyWith(color: c.textTertiary)),
                      ],
                    ],
                  ),
                ),
                if (trailing != null) ...[const SizedBox(width: Space.xs), trailing!],
                if (showChevron && trailing == null && onTap != null)
                  Icon(Icons.chevron_right_rounded, color: c.textTertiary, size: 22),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
