import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../app/ui/ui.dart';
import '../settings/legal_screen.dart';
import 'auth_widgets.dart';

/// Layar sambutan: wordmark kecil, satu kalimat besar serif sebagai fokus, penjelasan anonimitas,
/// lalu Buat akun / Masuk.
class WelcomeScreen extends StatefulWidget {
  const WelcomeScreen({super.key});

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {
  late final _terms = TapGestureRecognizer()..onTap = () => openLegal(context, LegalDoc.terms);
  late final _privacy = TapGestureRecognizer()..onTap = () => openLegal(context, LegalDoc.privacy);

  @override
  void dispose() {
    _terms.dispose();
    _privacy.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final small = context.text.bodySmall?.copyWith(color: c.textTertiary, fontWeight: FontWeight.w400);
    final link = small?.copyWith(
      color: c.textSecondary,
      fontWeight: FontWeight.w600,
      decoration: TextDecoration.underline,
      decorationColor: c.textTertiary,
    );
    final width = MediaQuery.sizeOf(context).width;
    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, box) => SingleChildScrollView(
            child: ConstrainedBox(
              constraints: BoxConstraints(minHeight: box.maxHeight),
              child: IntrinsicHeight(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(Space.gutter, Space.md, Space.gutter, Space.md),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    Text('Senyap', style: context.text.headlineSmall),
                    const Spacer(flex: 2),
                    Text(
                      'Tulis yang tak sempat terucap.',
                      style: context.text.displayMedium?.copyWith(fontSize: width < 360 ? 34 : 42),
                    ),
                    const SizedBox(height: Space.lg),
                    Text(
                      'Cerita dan obrolan tanpa nama. Orang lain hanya melihat aliasmu, '
                      'tidak pernah nomor HP, email, atau wajahmu.',
                      style: context.text.bodyLarge?.copyWith(color: c.textSecondary),
                    ),
                    const Spacer(flex: 2),
                    const SizedBox(height: Space.xxl),
                    PrimaryButton(
                      key: const Key('welcome-register'),
                      label: 'Buat akun',
                      onPressed: () => context.push(AppRoutes.register),
                    ),
                    const SizedBox(height: Space.sm),
                    SecondaryButton(
                      key: const Key('welcome-login'),
                      label: 'Aku sudah punya akun',
                      onPressed: () => context.push(AppRoutes.login),
                    ),
                    const SizedBox(height: Space.lg),
                    Text.rich(
                      TextSpan(style: small, children: [
                        const TextSpan(text: 'Dengan melanjutkan, kamu menyetujui '),
                        TextSpan(text: 'Syarat', style: link, recognizer: _terms),
                        const TextSpan(text: ' dan '),
                        TextSpan(text: 'Kebijakan Privasi', style: link, recognizer: _privacy),
                        const TextSpan(text: ' Senyap.'),
                      ]),
                    ),
                  ]),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
