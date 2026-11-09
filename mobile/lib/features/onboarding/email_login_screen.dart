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

/// Masuk dengan email + kata sandi; tautan Lupa kata sandi dan Masuk dengan nomor HP.
class EmailLoginScreen extends ConsumerStatefulWidget {
  const EmailLoginScreen({super.key});

  @override
  ConsumerState<EmailLoginScreen> createState() => _EmailLoginScreenState();
}

class _EmailLoginScreenState extends ConsumerState<EmailLoginScreen> {
  final _form = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    setState(() => _busy = true);
    try {
      final result =
          await ref.read(authRepositoryProvider).loginEmail(email: _email.text.trim(), password: _password.text);
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
      title: 'Masuk',
      subtitle: 'Senang kamu kembali. Masuk dengan email dan kata sandimu.',
      primaryLabel: 'Masuk',
      busy: _busy,
      onPrimary: _submit,
      footer: Padding(
        padding: const EdgeInsets.only(top: Space.sm),
        child: Column(children: [
          SecondaryButton(
            key: const Key('login-phone'),
            icon: Icons.smartphone_rounded,
            label: 'Masuk dengan nomor HP',
            onPressed: () => context.push(AppRoutes.loginPhone),
          ),
          AuthFooterLink(
            prompt: 'Belum punya akun?',
            action: 'Daftar',
            onTap: () => context.pushReplacement(AppRoutes.register),
          ),
        ]),
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
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => _submit(),
                validator: (v) => (v ?? '').isEmpty ? 'Kata sandi wajib diisi.' : null,
              ),
              const SizedBox(height: Space.xxs),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  key: const Key('forgot-password'),
                  onPressed: () => context.push(AppRoutes.forgotPassword, extra: _email.text.trim()),
                  child: const Text('Lupa kata sandi?'),
                ),
              ),
            ]),
          ),
        ),
      ],
    );
  }
}
