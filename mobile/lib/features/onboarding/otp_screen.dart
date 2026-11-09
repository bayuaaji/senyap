import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../core/api/api_error.dart';
import '../../core/auth/session.dart';
import '../../app/ui/ui.dart';
import 'auth_repository.dart';
import 'onboarding_draft.dart';
import 'onboarding_scaffold.dart';

/// Kode OTP (berlaku 5 menit, maksimal 3 percobaan). Setelah berhasil, router memindahkan ke Lengkapi
/// profil (belum onboarding) atau langsung ke Feed.
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
      context.go(AppRoutes.welcome);
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
      if (mounted) showInfo(context, 'Kode baru sudah dikirim.');
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    return OnboardingScaffold(
      title: 'Masukkan kode',
      subtitle: 'Kami sudah mengirim kode 6 digit ke ${ref.watch(onboardingDraftProvider).phone ?? 'nomormu'}. '
          'Kode berlaku 5 menit.',
      primaryLabel: 'Lanjut',
      busy: _busy,
      onPrimary: _verify,
      children: [
        OtpBoxes(controller: _controller, onSubmitted: (_) => _verify()),
        const SizedBox(height: Space.lg),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            style: TextButton.styleFrom(padding: const EdgeInsets.only(right: Space.sm)),
            onPressed: _resend,
            icon: const Icon(Icons.refresh_rounded, size: 18),
            label: const Text('Kirim ulang kode'),
          ),
        ),
      ],
    );
  }
}

/// Enam kotak digit di atas satu `TextField` tersembunyi (key `otp-field`), supaya autofill kode SMS, tempel,
/// dan hapus tetap bekerja seperti kolom biasa.
class OtpBoxes extends StatefulWidget {
  const OtpBoxes({super.key, required this.controller, this.onSubmitted, this.length = 6, this.fieldKey = const Key('otp-field')});

  final TextEditingController controller;
  final ValueChanged<String>? onSubmitted;
  final int length;
  final Key fieldKey;

  @override
  State<OtpBoxes> createState() => _OtpBoxesState();
}

class _OtpBoxesState extends State<OtpBoxes> {
  final _focus = FocusNode();

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_changed);
    _focus.addListener(_changed);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_changed);
    _focus.dispose();
    super.dispose();
  }

  void _changed() => setState(() {});

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final value = widget.controller.text;
    return SizedBox(
      height: 64,
      child: Stack(children: [
        Row(children: [
          for (var i = 0; i < widget.length; i++) ...[
            if (i > 0) const SizedBox(width: Space.xs),
            Expanded(child: _box(c, i, value)),
          ],
        ]),
        Positioned.fill(
          child: Opacity(
            opacity: 0.011,
            child: TextField(
              key: widget.fieldKey,
              controller: widget.controller,
              focusNode: _focus,
              autofocus: true,
              keyboardType: TextInputType.number,
              autofillHints: const [AutofillHints.oneTimeCode],
              maxLength: widget.length,
              showCursor: false,
              enableInteractiveSelection: false,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              onSubmitted: widget.onSubmitted,
              style: const TextStyle(color: Colors.transparent, fontSize: 1),
              decoration: const InputDecoration(
                counterText: '',
                filled: false,
                border: InputBorder.none,
                enabledBorder: InputBorder.none,
                focusedBorder: InputBorder.none,
                contentPadding: EdgeInsets.zero,
              ),
            ),
          ),
        ),
      ]),
    );
  }

  Widget _box(SenyapColors c, int i, String value) {
    final filled = i < value.length;
    final active = _focus.hasFocus && (i == value.length || (i == widget.length - 1 && value.length == widget.length));
    return AnimatedContainer(
      duration: Motion.fast,
      curve: Motion.curve,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: c.raised,
        borderRadius: Radii.all(Radii.md),
        border: Border.all(color: active ? c.text : Colors.transparent, width: 1.4),
      ),
      child: AnimatedSwitcher(
        duration: Motion.fast,
        transitionBuilder: (child, a) => ScaleTransition(scale: a, child: FadeTransition(opacity: a, child: child)),
        child: Text(
          filled ? value[i] : '',
          key: ValueKey('$i:${filled ? value[i] : ''}'),
          style: TextStyle(fontFamily: Fonts.sans, fontSize: 26, fontWeight: FontWeight.w700, color: c.text),
        ),
      ),
    );
  }
}
