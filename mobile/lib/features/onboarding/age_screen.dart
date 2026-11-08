import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import 'onboarding_draft.dart';
import 'onboarding_scaffold.dart';

/// Konfirmasi usia 18+ dan persetujuan syarat & kebijakan privasi (FS-01 langkah 2).
class AgeScreen extends ConsumerStatefulWidget {
  const AgeScreen({super.key});

  @override
  ConsumerState<AgeScreen> createState() => _AgeScreenState();
}

class _AgeScreenState extends ConsumerState<AgeScreen> {
  bool _adult = false;
  bool _terms = false;

  @override
  Widget build(BuildContext context) {
    return OnboardingScaffold(
      title: 'Sebelum mulai',
      primaryLabel: 'Setuju dan lanjut',
      onPrimary: _adult && _terms
          ? () {
              ref.read(onboardingDraftProvider.notifier).confirmAge();
              context.push(AppRoutes.phone);
            }
          : null,
      children: [
        CheckboxListTile(
          contentPadding: EdgeInsets.zero,
          value: _adult,
          onChanged: (v) => setState(() => _adult = v ?? false),
          title: const Text('Saya berusia 18 tahun ke atas.'),
        ),
        CheckboxListTile(
          contentPadding: EdgeInsets.zero,
          value: _terms,
          onChanged: (v) => setState(() => _terms = v ?? false),
          title: const Text('Saya setuju dengan Syarat Layanan dan Kebijakan Privasi Senyap.'),
        ),
      ],
    );
  }
}
