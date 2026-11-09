import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

import '../../core/api/api_error.dart';
import '../../app/ui/ui.dart';
import '../settings/legal_screen.dart';

/// Pesan error auth yang ramah; kode lain memakai pesan dari server.
String authErrorMessage(ApiError e) => switch (e.code) {
      'EMAIL_TAKEN' => 'Email ini sudah terdaftar. Silakan masuk.',
      'INVALID_CREDENTIALS' => 'Email atau kata sandi salah.',
      'WEAK_PASSWORD' => 'Kata sandi terlalu lemah. Pakai minimal 8 karakter, campur huruf dan angka.',
      'INVALID_EMAIL' => 'Format email tidak valid.',
      _ => e.message,
    };

final _emailPattern = RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$');

String? validateEmail(String? v) {
  final t = v?.trim() ?? '';
  if (t.isEmpty) return 'Email wajib diisi.';
  if (!_emailPattern.hasMatch(t)) return 'Format email tidak valid.';
  return null;
}

const minPasswordLength = 8;

String? validateNewPassword(String? v) =>
    (v ?? '').length < minPasswordLength ? 'Minimal $minPasswordLength karakter.' : null;

void openLegal(BuildContext context, LegalDoc doc) =>
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => LegalScreen(doc: doc)));

/// Kolom kata sandi dengan tombol tampilkan/sembunyikan.
class PasswordField extends StatefulWidget {
  const PasswordField({
    super.key,
    required this.controller,
    this.label = 'Kata sandi',
    this.validator,
    this.newPassword = false,
    this.textInputAction,
    this.onSubmitted,
  });

  final TextEditingController controller;
  final String label;
  final FormFieldValidator<String>? validator;
  final bool newPassword;
  final TextInputAction? textInputAction;
  final ValueChanged<String>? onSubmitted;

  @override
  State<PasswordField> createState() => _PasswordFieldState();
}

class _PasswordFieldState extends State<PasswordField> {
  bool _obscure = true;

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      controller: widget.controller,
      obscureText: _obscure,
      enableSuggestions: false,
      autocorrect: false,
      autofillHints: [widget.newPassword ? AutofillHints.newPassword : AutofillHints.password],
      textInputAction: widget.textInputAction,
      onFieldSubmitted: widget.onSubmitted,
      validator: widget.validator,
      decoration: InputDecoration(
        labelText: widget.label,
        helperText: widget.newPassword ? 'Minimal $minPasswordLength karakter.' : null,
        suffixIcon: IconButton(
          tooltip: _obscure ? 'Tampilkan kata sandi' : 'Sembunyikan kata sandi',
          padding: const EdgeInsets.only(right: 8),
          icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 20),
          onPressed: () => setState(() => _obscure = !_obscure),
        ),
      ),
    );
  }
}

/// Centang "18 tahun ke atas dan setuju Syarat & Kebijakan Privasi" dengan tautan ke dokumennya.
class ConsentCheckbox extends StatefulWidget {
  const ConsentCheckbox({super.key, required this.value, required this.onChanged});

  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  State<ConsentCheckbox> createState() => _ConsentCheckboxState();
}

class _ConsentCheckboxState extends State<ConsentCheckbox> {
  late final _terms = TapGestureRecognizer()..onTap = () => openLegal(context, LegalDoc.terms);
  late final _privacy = TapGestureRecognizer()..onTap = () => openLegal(context, LegalDoc.privacy);

  @override
  void dispose() {
    _terms.dispose();
    _privacy.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final link = TextStyle(
      color: c.text,
      fontWeight: FontWeight.w600,
      decoration: TextDecoration.underline,
      decorationColor: c.textTertiary,
    );
    final on = widget.value;
    return Semantics(
      checked: on,
      child: Pressable(
        key: const Key('consent-checkbox'),
        scale: 1,
        onTap: () => widget.onChanged(!on),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: Space.xs),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          AnimatedContainer(
            duration: Motion.fast,
            width: 24,
            height: 24,
            decoration: BoxDecoration(
              color: on ? c.text : Colors.transparent,
              borderRadius: Radii.all(6),
              border: Border.all(color: on ? c.text : c.textTertiary, width: 1.6),
            ),
            child: on ? Icon(Icons.check_rounded, size: 17, color: c.bg) : null,
          ),
          const SizedBox(width: Space.sm),
          Expanded(
            child: Text.rich(
              TextSpan(style: context.text.bodyMedium, children: [
                const TextSpan(text: 'Saya berusia 18 tahun ke atas dan setuju dengan '),
                TextSpan(text: 'Syarat', style: link, recognizer: _terms),
                const TextSpan(text: ' & '),
                TextSpan(text: 'Kebijakan Privasi', style: link, recognizer: _privacy),
                const TextSpan(text: '.'),
              ]),
            ),
          ),
        ]),
        ),
      ),
    );
  }
}

/// Tautan teks kecil di bawah tombol utama, mis. "Sudah punya akun? **Masuk**".
class AuthFooterLink extends StatelessWidget {
  const AuthFooterLink({super.key, required this.prompt, required this.action, required this.onTap});

  final String prompt;
  final String action;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: Space.xs),
        child: Center(
          child: TextButton(
            onPressed: onTap,
            child: Text.rich(TextSpan(children: [
              TextSpan(text: '$prompt ', style: TextStyle(color: context.colors.textSecondary, fontWeight: FontWeight.w500)),
              TextSpan(text: action, style: const TextStyle(fontWeight: FontWeight.w700)),
            ])),
          ),
        ),
      );
}
