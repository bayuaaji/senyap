import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_error.dart';
import '../../core/auth/session.dart';
import '../onboarding/onboarding_scaffold.dart';
import '../profile/alias_avatar.dart';
import '../profile/alias_editor.dart';

/// Pengaturan profil: alias (ganti maks. 1× per 7 hari), keluar, hapus akun.
class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profile = ref.watch(sessionProvider).profile;
    final changeableAt = profile?.aliasChangeableAt;
    return Scaffold(
      appBar: AppBar(title: const Text('Pengaturan')),
      body: ListView(
        children: [
          if (profile != null)
            ListTile(
              leading: AliasAvatar(alias: profile.alias, radius: 20),
              title: Text(profile.alias.display),
              subtitle: const Text('Alias kamu'),
            ),
          ListTile(
            leading: const Icon(Icons.badge_outlined),
            title: const Text('Ganti alias'),
            subtitle: Text(changeableAt == null
                ? 'Bisa diganti seminggu sekali.'
                : 'Bisa diganti lagi mulai ${_date(changeableAt.toLocal())}.'),
            enabled: profile != null && changeableAt == null,
            onTap: () => _changeAlias(context, ref),
          ),
          const Divider(),
          ListTile(
            leading: const Icon(Icons.logout),
            title: const Text('Keluar'),
            onTap: () => ref.read(sessionProvider.notifier).logout(),
          ),
          ListTile(
            leading: Icon(Icons.delete_forever_outlined, color: Theme.of(context).colorScheme.error),
            title: Text('Hapus akun', style: TextStyle(color: Theme.of(context).colorScheme.error)),
            onTap: () => _confirmDelete(context, ref),
          ),
        ],
      ),
    );
  }

  static String _date(DateTime d) {
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
    return '${d.day} ${months[d.month - 1]} ${d.year}';
  }

  Future<void> _changeAlias(BuildContext context, WidgetRef ref) {
    final current = ref.read(sessionProvider).profile?.alias;
    return Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (context) => Scaffold(
        appBar: AppBar(title: const Text('Ganti alias')),
        body: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const Text('Semua post, balasan, dan Bisik lamamu ikut memakai alias baru. '
                'Setelah diganti, alias baru bisa diganti lagi seminggu kemudian.'),
            AliasEditor(
              initial: current,
              onSaved: (profile) {
                ref.read(sessionProvider.notifier).profileUpdated(profile);
                Navigator.of(context).pop();
              },
            ),
          ],
        ),
      ),
    ));
  }

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
    return AlertDialog(
      title: const Text('Hapus akun?'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Akunmu dihapus permanen dalam 30 hari. Post, balasan, dan pesanmu akan tampil '
              'sebagai "[dihapus]". Kalau kamu masuk lagi dengan nomor yang sama dalam 29 hari, '
              'penghapusan dibatalkan dan akunmu kembali seperti semula. Ketik HAPUS untuk mengonfirmasi.'),
          const SizedBox(height: 12),
          TextField(key: const Key('delete-confirm'), controller: _controller, autofocus: true),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Batal')),
        ValueListenableBuilder(
          valueListenable: _controller,
          builder: (context, value, _) => TextButton(
            onPressed: value.text == 'HAPUS' ? () => Navigator.pop(context, true) : null,
            child: const Text('Hapus'),
          ),
        ),
      ],
    );
  }
}
