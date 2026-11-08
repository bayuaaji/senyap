import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import 'onboarding_scaffold.dart';

/// Izin lokasi (opsional) dan notifikasi (FS-01 langkah 6). Permintaan izin OS menyusul saat fiturnya
/// ada: notifikasi push di Fase 2 (FCM/APNs), lokasi untuk feed Sekitar di Fase 3.
class PermissionsScreen extends StatelessWidget {
  const PermissionsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return OnboardingScaffold(
      title: 'Izin (opsional)',
      showBack: false,
      primaryLabel: 'Mulai',
      onPrimary: () => context.go(AppRoutes.feed),
      children: const [
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(Icons.notifications_outlined),
          title: Text('Notifikasi'),
          subtitle: Text('Supaya kamu tahu saat ada Bisik atau balasan baru. Kami akan bertanya saat dibutuhkan.'),
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(Icons.place_outlined),
          title: Text('Lokasi'),
          subtitle: Text('Hanya untuk feed Sekitar, dan hanya setingkat kota. Boleh tidak diizinkan.'),
        ),
      ],
    );
  }
}
