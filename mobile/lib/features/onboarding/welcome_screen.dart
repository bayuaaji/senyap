import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import 'onboarding_scaffold.dart';

/// Layar pembuka: model anonimitas (requirements §2, A1–A6) dalam bahasa sederhana.
class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({super.key});

  static const points = [
    (Icons.visibility_off_outlined, 'Orang lain tidak pernah melihat nomor HP, nama, atau profilmu.'),
    (Icons.pets_outlined,
        'Kamu tampil sebagai alias acak, misalnya "Kelinci Jingga #4821". Alias yang sama dipakai di semua '
            'post, balasan, dan Bisik, jadi aktivitasmu dengan alias itu bisa dikaitkan satu sama lain.'),
    (Icons.place_outlined, 'Lokasi hanya ditampilkan kasar, setingkat kota.'),
    (Icons.image_outlined, 'Data tersembunyi di foto (lokasi, jenis kamera) dihapus.'),
    (Icons.schedule_outlined, 'Waktu ditampilkan kira-kira, misalnya "5 menit yang lalu".'),
    (Icons.shield_outlined,
        'Anonim ke sesama pengguna, bukan ke sistem. Kami menyimpan sidik nomor HP-mu (bukan nomor aslinya) '
            'untuk menindak penyalahgunaan dan memenuhi permintaan hukum yang sah.'),
  ];

  @override
  Widget build(BuildContext context) {
    return OnboardingScaffold(
      title: 'Selamat datang di Senyap',
      showBack: false,
      primaryLabel: 'Lanjut',
      onPrimary: () => context.push(AppRoutes.age),
      children: [
        for (final (icon, text) in points)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(icon),
            title: Text(text),
          ),
      ],
    );
  }
}
