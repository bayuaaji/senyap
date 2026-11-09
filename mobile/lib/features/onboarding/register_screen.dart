import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../app/ui/ui.dart';
import 'auth_widgets.dart';

/// Daftar: pilih metode email atau nomor HP.
class RegisterScreen extends StatelessWidget {
  const RegisterScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Scaffold(
      appBar: const SenyapBar(),
      body: ListView(padding: const EdgeInsets.only(top: Space.md, bottom: Space.xl), children: [
        Padding(
          padding: Space.screen,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Buat akun', style: context.text.headlineLarge),
            const SizedBox(height: Space.sm),
            Text(
              'Email atau nomor HP hanya untuk masuk. Tidak pernah ditampilkan ke siapa pun. '
              'Di Senyap, kamu hanya dikenal lewat aliasmu.',
              style: context.text.bodyMedium,
            ),
          ]),
        ),
        const SizedBox(height: Space.xxl),
        Divider(color: c.line),
        GroupCard(children: [
          GroupTile(
            key: const Key('register-email'),
            icon: Icons.alternate_email_rounded,
            title: 'Lanjut dengan email',
            subtitle: 'Pakai kata sandi. Bisa dipulihkan lewat email.',
            onTap: () => context.push(AppRoutes.registerEmail),
          ),
          GroupTile(
            key: const Key('register-phone'),
            icon: Icons.smartphone_rounded,
            title: 'Lanjut dengan nomor HP',
            subtitle: 'Masuk dengan kode 6 digit lewat SMS.',
            onTap: () => context.push(AppRoutes.registerPhone),
          ),
        ]),
        Divider(color: c.line),
        Padding(
          padding: const EdgeInsets.fromLTRB(Space.gutter, Space.lg, Space.gutter, 0),
          child: Text(
            'Nomor HP disimpan sebagai sandi satu arah, bukan nomor aslinya.',
            style: context.text.bodySmall?.copyWith(color: c.textTertiary, fontWeight: FontWeight.w400),
          ),
        ),
        const SizedBox(height: Space.xl),
        AuthFooterLink(
          prompt: 'Sudah punya akun?',
          action: 'Masuk',
          onTap: () => context.pushReplacement(AppRoutes.login),
        ),
      ]),
    );
  }
}
