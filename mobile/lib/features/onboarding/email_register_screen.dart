import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';

import '../../core/api/api_error.dart';
import '../../core/auth/session.dart';
import '../../app/ui/ui.dart';
import 'auth_repository.dart';
import 'auth_widgets.dart';
import 'onboarding_scaffold.dart';

/// Daftar dengan email + kata sandi. Setelah berhasil, router memindahkan ke Lengkapi profil.
class EmailRegisterScreen extends ConsumerStatefulWidget {
  const EmailRegisterScreen({super.key});

  @override
  ConsumerState<EmailRegisterScreen> createState() => _EmailRegisterScreenState();
}

class _EmailRegisterScreenState extends ConsumerState<EmailRegisterScreen> {
  final _form = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  bool _consent = false;
  bool _busy = false;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    setState(() => _busy = true);
    try {
      final result = await ref
          .read(authRepositoryProvider)
          .registerEmail(email: _email.text.trim(), password: _password.text);
      if (mounted) ScaffoldMessenger.of(context).hideCurrentSnackBar();
      await ref.read(sessionProvider.notifier).signedIn(result);
    } on ApiError catch (e) {
      if (mounted) showError(context, authErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return OnboardingScaffold(
      title: 'Daftar dengan email',
      subtitle: 'Emailmu hanya untuk masuk dan memulihkan akun. Tidak pernah ditampilkan ke pengguna lain.',
      primaryLabel: 'Buat akun',
      busy: _busy,
      onPrimary: _consent ? _submit : null,
      footer: AuthFooterLink(
        prompt: 'Sudah punya akun?',
        action: 'Masuk',
        onTap: () => context.pushReplacement(AppRoutes.login),
      ),
      children: [
        Form(
          key: _form,
          child: AutofillGroup(
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              TextFormField(
                key: const Key('email-field'),
                controller: _email,
                keyboardType: TextInputType.emailAddress,
                autofillHints: const [AutofillHints.email],
                autocorrect: false,
                textInputAction: TextInputAction.next,
                validator: validateEmail,
                decoration: const InputDecoration(labelText: 'Email'),
              ),
              const SizedBox(height: Space.sm),
              PasswordField(
                key: const Key('password-field'),
                controller: _password,
                newPassword: true,
                textInputAction: TextInputAction.next,
                validator: validateNewPassword,
              ),
              const SizedBox(height: Space.sm),
              PasswordField(
                key: const Key('password-confirm-field'),
                controller: _confirm,
                label: 'Konfirmasi kata sandi',
                textInputAction: TextInputAction.done,
                validator: (v) => v != _password.text ? 'Kata sandi tidak sama.' : null,
              ),
              const SizedBox(height: Space.lg),
              ConsentCheckbox(value: _consent, onChanged: (v) => setState(() => _consent = v)),
            ]),
          ),
        ),
      ],
    );
  }
}
