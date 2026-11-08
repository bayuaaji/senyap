import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../core/auth/session.dart';
import 'alias_editor.dart';

/// Buat profil (akun baru, FS-01 langkah 5). "Acak lagi" tanpa batas di langkah ini.
class CreateProfileScreen extends ConsumerWidget {
  const CreateProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: const Text('Alias kamu')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const Text('Alias ini yang dilihat orang lain di post, balasan, dan Bisik. '
                'Setelah dipilih, alias hanya bisa diganti seminggu sekali.'),
            AliasEditor(
              onSaved: (profile) {
                ref.read(sessionProvider.notifier).profileUpdated(profile);
                context.go(AppRoutes.permissions);
              },
            ),
          ],
        ),
      ),
    );
  }
}
