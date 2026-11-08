import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../core/api/api_error.dart';
import '../../core/auth/session.dart';
import 'auth_repository.dart';
import 'onboarding_draft.dart';
import 'onboarding_scaffold.dart';

/// Kode OTP (berlaku 5 menit, maksimal 3 percobaan). Setelah berhasil, router memindahkan ke layar
/// buat profil (akun baru) atau langsung ke Feed (akun lama).
class OtpScreen extends ConsumerStatefulWidget {
  const OtpScreen({super.key});

  @override
  ConsumerState<OtpScreen> createState() => _OtpScreenState();
}

class _OtpScreenState extends ConsumerState<OtpScreen> {
  final _controller = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _verify() async {
    final phone = ref.read(onboardingDraftProvider).phone;
    if (phone == null) {
      context.go(AppRoutes.phone);
      return;
    }
    setState(() => _busy = true);
    try {
      final result = await ref.read(authRepositoryProvider).verify(phone: phone, code: _controller.text);
      if (mounted) ScaffoldMessenger.of(context).hideCurrentSnackBar();
      ref.read(onboardingDraftProvider.notifier).reset();
      await ref.read(sessionProvider.notifier).signedIn(result);
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resend() async {
    final phone = ref.read(onboardingDraftProvider).phone;
    if (phone == null) return;
    try {
      await ref.read(authRepositoryProvider).requestOtp(phone);
      if (mounted) showError(context, 'Kode baru sudah dikirim.');
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    return OnboardingScaffold(
      title: 'Masukkan kode',
      primaryLabel: 'Masuk',
      busy: _busy,
      onPrimary: _verify,
      children: [
        const Text('Kode 6 digit berlaku 5 menit.'),
        const SizedBox(height: 16),
        TextField(
          key: const Key('otp-field'),
          controller: _controller,
          keyboardType: TextInputType.number,
          autofillHints: const [AutofillHints.oneTimeCode],
          maxLength: 6,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: const InputDecoration(labelText: 'Kode', border: OutlineInputBorder()),
          onSubmitted: (_) => _verify(),
        ),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton(onPressed: _resend, child: const Text('Kirim ulang kode')),
        ),
      ],
    );
  }
}
