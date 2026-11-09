import 'package:flutter/material.dart';

import '../../app/ui/ui.dart';

/// Kerangka layar onboarding: judul serif, subjudul, isi yang bisa di-scroll, tombol utama menempel di bawah.
class OnboardingScaffold extends StatelessWidget {
  const OnboardingScaffold({
    super.key,
    required this.title,
    required this.children,
    required this.primaryLabel,
    required this.onPrimary,
    this.subtitle,
    this.busy = false,
    this.showBack = true,
    this.footer,
    this.top,
    this.bar,
  });

  final String title;
  final String? subtitle;
  final List<Widget> children;
  final String primaryLabel;
  final VoidCallback? onPrimary;
  final bool busy;
  final bool showBack;

  /// Di bawah tombol utama (mis. tautan "Sudah punya akun? Masuk").
  final Widget? footer;

  /// Di atas judul (mis. indikator progres langkah).
  final Widget? top;

  /// Ganti app bar bawaan.
  final PreferredSizeWidget? bar;

  @override
  Widget build(BuildContext context) {
    final hasBar = bar != null || showBack;
    return Scaffold(
      appBar: bar ?? (showBack ? const SenyapBar() : null),
      body: SafeArea(
        top: !hasBar,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Expanded(
            child: ListView(
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: EdgeInsets.fromLTRB(Space.gutter, hasBar ? Space.md : Space.xl, Space.gutter, Space.xl),
              children: [
                if (top != null) ...[top!, const SizedBox(height: Space.xl)],
                Text(title, style: context.text.headlineLarge),
                if (subtitle != null) ...[
                  const SizedBox(height: Space.sm),
                  Text(subtitle!, style: context.text.bodyMedium),
                ],
                const SizedBox(height: Space.xxl),
                Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(Space.gutter, Space.sm, Space.gutter, Space.md),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              PrimaryButton(label: primaryLabel, busy: busy, onPressed: onPrimary),
              ?footer,
            ]),
          ),
        ]),
      ),
    );
  }
}

/// Pesan error singkat (toast).
void showError(BuildContext context, String message) => showToast(context, message, tone: ToastTone.error);

/// Pesan berhasil.
void showInfo(BuildContext context, String message) => showToast(context, message, tone: ToastTone.success);
