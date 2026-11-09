import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme/theme.dart';

enum ToastTone { neutral, success, error }

/// Toast melayang (SnackBar) berlatar tinta. Ikon hanya untuk berhasil/gagal, supaya status tidak hanya
/// dibedakan lewat warna.
void showToast(BuildContext context, String message, {ToastTone tone = ToastTone.neutral, IconData? icon}) {
  final messenger = ScaffoldMessenger.maybeOf(context);
  if (messenger == null) return;
  final c = context.colors;
  final shown = icon ??
      switch (tone) {
        ToastTone.neutral => null,
        ToastTone.success => Icons.check_rounded,
        ToastTone.error => Icons.error_outline_rounded,
      };
  if (tone == ToastTone.error) HapticFeedback.mediumImpact();
  messenger
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(
      duration: const Duration(milliseconds: 3200),
      padding: const EdgeInsets.symmetric(horizontal: Space.md, vertical: 14),
      content: Row(children: [
        if (shown != null) ...[
          Icon(shown, size: 19, color: c.bg),
          const SizedBox(width: Space.sm),
        ],
        Expanded(child: Text(message)),
      ]),
    ));
}

/// Dialog konfirmasi. [destructive] mewarnai tombol konfirmasi merah. [icon] diterima demi kompatibilitas,
/// tetapi tidak digambar (judul sudah menyebut aksinya).
Future<bool> showConfirm(
  BuildContext context, {
  required String title,
  required String message,
  required String confirmLabel,
  String cancelLabel = 'Batal',
  bool destructive = false,
  Key? confirmKey,
  IconData? icon,
}) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => SenyapDialog(
        destructive: destructive,
        title: title,
        message: message,
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            style: TextButton.styleFrom(foregroundColor: context.colors.textSecondary),
            child: Text(cancelLabel),
          ),
          FilledButton(
            key: confirmKey,
            style: destructive
                ? FilledButton.styleFrom(backgroundColor: context.colors.danger, foregroundColor: context.colors.bg)
                : null,
            onPressed: () => Navigator.pop(context, true),
            child: Text(confirmLabel),
          ),
        ],
      ),
    ) ==
    true;

/// Kerangka dialog: judul serif, pesan, konten tambahan, tombol rata kanan.
class SenyapDialog extends StatelessWidget {
  const SenyapDialog({
    super.key,
    required this.title,
    this.message,
    this.content,
    this.actions = const [],
    this.icon,
    this.destructive = false,
  });

  final String title;
  final String? message;
  final Widget? content;
  final List<Widget> actions;

  /// Tidak digambar; dipertahankan supaya pemanggil lama tetap kompatibel.
  final IconData? icon;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(Space.xl, Space.xl, Space.lg, Space.md),
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Padding(
              padding: const EdgeInsets.only(right: Space.xs),
              child: Text(title, style: context.text.headlineSmall),
            ),
            if (message != null) ...[
              const SizedBox(height: Space.sm),
              Padding(
                padding: const EdgeInsets.only(right: Space.xs),
                child: Text(message!, style: context.text.bodyMedium),
              ),
            ],
            if (content != null) ...[
              const SizedBox(height: Space.md),
              Padding(padding: const EdgeInsets.only(right: Space.xs), child: content!),
            ],
            if (actions.isNotEmpty) ...[
              const SizedBox(height: Space.xl),
              Wrap(
                alignment: WrapAlignment.end,
                spacing: Space.xs,
                runSpacing: Space.xs,
                children: actions,
              ),
            ],
          ]),
        ),
      ),
    );
  }
}

/// Bottom sheet dengan handle; isi dibungkus SafeArea.
Future<T?> showSenyapSheet<T>(BuildContext context, {required WidgetBuilder builder, bool scrollable = false}) =>
    showModalBottomSheet<T>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: scrollable,
      builder: (context) => SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.only(bottom: Space.md),
          child: builder(context),
        ),
      ),
    );

/// Judul di dalam sheet.
class SheetHeader extends StatelessWidget {
  const SheetHeader(this.title, {super.key, this.subtitle});

  final String title;
  final String? subtitle;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(Space.gutter, 0, Space.gutter, Space.md),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: context.text.headlineSmall),
          if (subtitle != null) ...[const SizedBox(height: Space.xxs), Text(subtitle!, style: context.text.bodyMedium)],
        ]),
      );
}

/// Aksi di dalam sheet: ikon polos, judul, keterangan.
class SheetAction extends StatelessWidget {
  const SheetAction({super.key, required this.icon, required this.title, this.subtitle, required this.onTap, this.danger = false});

  final IconData icon;
  final String title;
  final String? subtitle;
  final VoidCallback onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final tint = danger ? c.danger : c.text;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(Space.gutter, Space.md, Space.gutter, Space.md),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.only(top: 1),
            child: Icon(icon, size: 22, color: danger ? c.danger : c.textSecondary),
          ),
          const SizedBox(width: Space.md),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: context.text.titleMedium?.copyWith(color: tint)),
              if (subtitle != null) ...[
                const SizedBox(height: 3),
                Text(subtitle!, style: context.text.bodyMedium),
              ],
            ]),
          ),
        ]),
      ),
    );
  }
}
