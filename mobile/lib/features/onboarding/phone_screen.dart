import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../core/api/api_error.dart';
import '../../app/ui/ui.dart';
import 'auth_repository.dart';
import 'auth_widgets.dart';
import 'onboarding_draft.dart';
import 'onboarding_scaffold.dart';

/// Nomor HP → kode OTP. [register] = daftar (wajib centang 18+ dan setuju syarat); selain itu masuk.
class PhoneScreen extends ConsumerStatefulWidget {
  const PhoneScreen({super.key, required this.register});

  final bool register;

  @override
  ConsumerState<PhoneScreen> createState() => _PhoneScreenState();
}

class _PhoneScreenState extends ConsumerState<PhoneScreen> {
  final _controller = TextEditingController();
  bool _consent = false;
  bool _busy = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final phone = _controller.text.trim();
    if (phone.isEmpty) {
      showError(context, 'Nomor HP wajib diisi.');
      return;
    }
    setState(() => _busy = true);
    try {
      await ref.read(authRepositoryProvider).requestOtp(phone);
      ref.read(onboardingDraftProvider.notifier).setPhone(phone);
      if (mounted) context.push(AppRoutes.otp);
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return OnboardingScaffold(
      title: widget.register ? 'Daftar dengan nomor HP' : 'Masuk dengan nomor HP',
      subtitle: 'Kami kirim kode 6 digit lewat SMS. Nomormu tidak pernah ditampilkan ke siapa pun.',
      primaryLabel: 'Kirim kode',
      busy: _busy,
      onPrimary: !widget.register || _consent ? _submit : null,
      children: [
        TextField(
          key: const Key('phone-field'),
          controller: _controller,
          keyboardType: TextInputType.phone,
          autofillHints: const [AutofillHints.telephoneNumber],
          inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9+ -]'))],
          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600, letterSpacing: 0.6),
          decoration: const InputDecoration(
            labelText: 'Nomor HP',
            hintText: '0812 3456 7890',
          ),
          onSubmitted: (_) => !widget.register || _consent ? _submit() : null,
        ),
        const SizedBox(height: Space.xl),
        if (widget.register)
          ConsentCheckbox(value: _consent, onChanged: (v) => setState(() => _consent = v))
        else
          Text(
            'Nomor yang belum terdaftar akan dibuatkan akun baru. Dengan melanjutkan, kamu menyatakan berusia '
            '18 tahun ke atas dan setuju dengan Syarat & Kebijakan Privasi.',
            style: theme.textTheme.bodySmall?.copyWith(color: context.colors.textTertiary),
          ),
      ],
    );
  }
}
