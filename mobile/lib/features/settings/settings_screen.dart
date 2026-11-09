import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/shell.dart' show navClearance;
import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../../core/auth/session.dart';
import '../onboarding/onboarding_scaffold.dart';
import '../profile/alias_avatar.dart';
import '../profile/profile_screen.dart';
import 'blocks_screen.dart';
import 'legal_screen.dart';

/// Pengaturan: Profil (alias, gender, avatar), daftar blokir, dokumen legal, keluar, hapus akun.
class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profile = ref.watch(sessionProvider).profile;
    final c = context.colors;
    const legalIcons = {
      LegalDoc.terms: Icons.description_outlined,
      LegalDoc.privacy: Icons.privacy_tip_outlined,
      LegalDoc.guidelines: Icons.menu_book_outlined,
    };
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: ListView(
          padding: EdgeInsets.only(bottom: navClearance(context)),
          children: [
            const ScreenTitle('Pengaturan'),
            if (profile != null)
              Material(
                type: MaterialType.transparency,
                child: InkWell(
                  key: const Key('settings-profile'),
                  onTap: () => _push(context, const ProfileScreen()),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(Space.gutter, Space.sm, Space.md, Space.sm),
                    child: Row(children: [
                      AliasAvatar(alias: profile.alias, emoji: profile.avatar?.emoji, radius: 28),
                      const SizedBox(width: Space.md),
                      Expanded(
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          AliasName(text: profile.alias.display, gender: profile.gender, style: context.text.headlineSmall),
                          const SizedBox(height: 2),
                          Text('Ubah alias, gender, dan avatar', style: context.text.bodySmall),
                        ]),
                      ),
                      Icon(Icons.chevron_right_rounded, color: c.textTertiary),
                    ]),
                  ),
                ),
              ),
            const SectionLabel('Keamanan'),
            GroupCard(children: [
              GroupTile(
                key: const Key('settings-blocks'),
                icon: Icons.block_rounded,
                title: 'Akun yang diblokir',
                subtitle: 'Mereka tidak bisa melihat kontenmu atau berbisik kepadamu.',
                onTap: () => _push(context, const BlocksScreen()),
              ),
            ]),
            const SectionLabel('Tentang'),
            GroupCard(children: [
              for (final doc in LegalDoc.values)
                GroupTile(
                  key: Key('legal-${doc.name}'),
                  icon: legalIcons[doc]!,
                  title: doc.title,
                  onTap: () => _push(context, LegalScreen(doc: doc)),
                ),
            ]),
            const SectionLabel('Akun'),
            GroupCard(children: [
              GroupTile(
                icon: Icons.logout_rounded,
                title: 'Keluar',
                showChevron: false,
                onTap: () => ref.read(sessionProvider.notifier).logout(),
              ),
              GroupTile(
                icon: Icons.delete_outline_rounded,
                title: 'Hapus akun',
                danger: true,
                showChevron: false,
                onTap: () => _confirmDelete(context, ref),
              ),
            ]),
            const SizedBox(height: Space.x3),
            Padding(
              padding: Space.screen,
              child: Text('Senyap versi 0.1',
                  style: context.text.bodySmall?.copyWith(color: c.textTertiary, fontWeight: FontWeight.w400)),
            ),
          ],
        ),
      ),
    );
  }

  static Future<void> _push(BuildContext context, Widget screen) =>
      Navigator.of(context, rootNavigator: true).push(MaterialPageRoute<void>(builder: (_) => screen));

  Future<void> _confirmDelete(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(context: context, builder: (context) => const _DeleteDialog());
    if (confirmed != true) return;
    try {
      await ref.read(sessionProvider.notifier).deleteAccount();
    } on ApiError catch (e) {
      if (context.mounted) showError(context, e.message);
    }
  }
}

class _DeleteDialog extends StatefulWidget {
  const _DeleteDialog();

  @override
  State<_DeleteDialog> createState() => _DeleteDialogState();
}

class _DeleteDialogState extends State<_DeleteDialog> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return SenyapDialog(
      icon: Icons.delete_outline_rounded,
      destructive: true,
      title: 'Hapus akun?',
      message: 'Akunmu dihapus permanen dalam 30 hari. Post, balasan, dan pesanmu akan tampil sebagai "[dihapus]". '
          'Kalau kamu masuk lagi dalam 29 hari, penghapusan dibatalkan dan akunmu kembali seperti semula.',
      content: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text.rich(TextSpan(style: context.text.bodySmall, children: [
          const TextSpan(text: 'Ketik '),
          TextSpan(text: 'HAPUS', style: TextStyle(color: c.danger, fontWeight: FontWeight.w700, letterSpacing: 1)),
          const TextSpan(text: ' untuk mengonfirmasi.'),
        ])),
        const SizedBox(height: Space.sm),
        TextField(
          key: const Key('delete-confirm'),
          controller: _controller,
          autofocus: true,
          textCapitalization: TextCapitalization.characters,
          decoration: const InputDecoration(hintText: 'HAPUS'),
        ),
      ]),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          style: TextButton.styleFrom(foregroundColor: c.textSecondary),
          child: const Text('Batal'),
        ),
        ValueListenableBuilder(
          valueListenable: _controller,
          builder: (context, value, _) => TextButton(
            style: TextButton.styleFrom(
              backgroundColor: value.text == 'HAPUS' ? c.danger : c.raised,
              foregroundColor: c.bg,
              disabledForegroundColor: c.textTertiary,
              minimumSize: const Size(96, 48),
              padding: const EdgeInsets.symmetric(horizontal: Space.lg),
            ),
            onPressed: value.text == 'HAPUS' ? () => Navigator.pop(context, true) : null,
            child: const Text('Hapus'),
          ),
        ),
      ],
    );
  }
}
