import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../../app/ui/ui.dart';
import 'auth_repository.dart';
import 'auth_widgets.dart';
import 'onboarding_scaffold.dart';

/// Lupa kata sandi: (1) email → kode dikirim, (2) kode 6 digit + kata sandi baru → kembali ke Masuk.
class ForgotPasswordScreen extends ConsumerStatefulWidget {
  const ForgotPasswordScreen({super.key, this.initialEmail});

  final String? initialEmail;

  @override
  ConsumerState<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends ConsumerState<ForgotPasswordScreen> {
  final _form = GlobalKey<FormState>();
  late final _email = TextEditingController(text: widget.initialEmail);
  final _code = TextEditingController();
  final _password = TextEditingController();
  bool _codeSent = false;
  bool _busy = false;

  @override
  void dispose() {
    _email.dispose();
    _code.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    setState(() => _busy = true);
    final repo = ref.read(authRepositoryProvider);
    try {
      if (!_codeSent) {
        await repo.forgotPassword(_email.text.trim());
        if (mounted) setState(() => _codeSent = true);
      } else {
        await repo.resetPassword(email: _email.text.trim(), code: _code.text, newPassword: _password.text);
        if (!mounted) return;
        showInfo(context, 'Kata sandi sudah diganti. Silakan masuk dengan kata sandi baru.');
        context.pop();
      }
    } on ApiError catch (e) {
      if (mounted) showError(context, authErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return OnboardingScaffold(
      title: 'Lupa kata sandi',
      subtitle: _codeSent
          ? 'Kalau email ini terdaftar, kami sudah mengirim kode 6 digit. Masukkan kode dan kata sandi baru.'
          : 'Masukkan email akunmu. Kami akan mengirim kode untuk mengganti kata sandi.',
      primaryLabel: _codeSent ? 'Simpan kata sandi baru' : 'Kirim kode',
      busy: _busy,
      onPrimary: _submit,
      children: [
        Form(
          key: _form,
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            TextFormField(
              key: const Key('email-field'),
              controller: _email,
              enabled: !_codeSent,
              keyboardType: TextInputType.emailAddress,
              autofillHints: const [AutofillHints.email],
              autocorrect: false,
              validator: validateEmail,
              decoration: const InputDecoration(labelText: 'Email'),
            ),
            if (_codeSent) ...[
              const SizedBox(height: Space.sm),
              TextFormField(
                key: const Key('reset-code-field'),
                controller: _code,
                keyboardType: TextInputType.number,
                autofillHints: const [AutofillHints.oneTimeCode],
                maxLength: 6,
                style: const TextStyle(fontSize: 20, letterSpacing: 8, fontWeight: FontWeight.w600),
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                validator: (v) => (v ?? '').length != 6 ? 'Kode terdiri dari 6 digit.' : null,
                decoration: const InputDecoration(labelText: 'Kode 6 digit', counterText: ''),
              ),
              const SizedBox(height: Space.sm),
              PasswordField(
                key: const Key('new-password-field'),
                controller: _password,
                label: 'Kata sandi baru',
                newPassword: true,
                validator: validateNewPassword,
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  onPressed: _busy
                      ? null
                      : () async {
                          try {
                            await ref.read(authRepositoryProvider).forgotPassword(_email.text.trim());
                            if (context.mounted) showInfo(context, 'Kode baru sudah dikirim.');
                          } on ApiError catch (e) {
                            if (context.mounted) showError(context, authErrorMessage(e));
                          }
                        },
                  child: const Text('Kirim ulang kode'),
                ),
              ),
            ],
          ]),
        ),
      ],
    );
  }
}
