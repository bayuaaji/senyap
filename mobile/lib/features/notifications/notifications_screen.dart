import 'package:flutter/material.dart';

import '../../app/shell.dart' show navClearance;
import '../../app/ui/ui.dart';

/// Notifikasi (belum ada sumber data di client): empty state yang menjelaskan apa yang akan muncul.
class NotificationsScreen extends StatelessWidget {
  const NotificationsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    Widget hint(IconData icon, String title, String body) => Padding(
          padding: const EdgeInsets.fromLTRB(Space.gutter, Space.md, Space.gutter, Space.md),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Padding(
              padding: const EdgeInsets.only(top: 1),
              child: Icon(icon, size: 22, color: c.textSecondary),
            ),
            const SizedBox(width: Space.md),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(title, style: context.text.titleMedium),
                const SizedBox(height: 2),
                Text(body, style: context.text.bodyMedium),
              ]),
            ),
          ]),
        );
    Widget divider() => Padding(padding: const EdgeInsets.only(left: Space.gutter), child: Divider(color: c.line));
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: ListView(padding: EdgeInsets.only(bottom: navClearance(context)), children: [
          const ScreenTitle('Notifikasi'),
          Padding(
            padding: Space.screen,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('Belum ada notifikasi.', style: context.text.headlineSmall),
              const SizedBox(height: Space.xs),
              Text('Kabar tentang cerita dan bisikanmu akan muncul di sini.', style: context.text.bodyMedium),
            ]),
          ),
          const SectionLabel('Yang akan muncul di sini'),
          hint(Icons.mode_comment_outlined, 'Balasan baru', 'Saat seseorang menanggapi ceritamu.'),
          divider(),
          hint(Icons.lock_outline_rounded, 'Permintaan Bisik', 'Saat seseorang ingin mengobrol berdua denganmu.'),
          divider(),
          hint(Icons.flag_outlined, 'Hasil laporan', 'Saat laporanmu selesai ditinjau.'),
        ]),
      ),
    );
  }
}
