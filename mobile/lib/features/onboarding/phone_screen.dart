import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../core/api/api_error.dart';
import 'auth_repository.dart';
import 'onboarding_draft.dart';
import 'onboarding_scaffold.dart';

class PhoneScreen extends ConsumerStatefulWidget {
  const PhoneScreen({super.key});

  @override
  ConsumerState<PhoneScreen> createState() => _PhoneScreenState();
}

class _PhoneScreenState extends ConsumerState<PhoneScreen> {
  final _controller = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final phone = _controller.text.trim();
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
    return OnboardingScaffold(
      title: 'Nomor HP kamu',
      primaryLabel: 'Kirim kode',
      busy: _busy,
      onPrimary: _submit,
      children: [
        const Text('Kami mengirim kode 6 digit untuk memastikan satu nomor hanya punya satu akun. '
            'Nomormu tidak pernah ditampilkan ke siapa pun.'),
        const SizedBox(height: 16),
        TextField(
          key: const Key('phone-field'),
          controller: _controller,
          keyboardType: TextInputType.phone,
          autofillHints: const [AutofillHints.telephoneNumber],
          inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9+ -]'))],
          decoration: const InputDecoration(
            labelText: 'Nomor HP',
            hintText: '0812 3456 7890',
            border: OutlineInputBorder(),
          ),
          onSubmitted: (_) => _submit(),
        ),
      ],
    );
  }
}
