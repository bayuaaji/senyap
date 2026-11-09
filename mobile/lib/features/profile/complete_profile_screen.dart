import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../../core/auth/session.dart';
import '../onboarding/onboarding_scaffold.dart';
import 'alias_avatar.dart';
import 'alias_editor.dart';
import 'profile_models.dart';
import 'profile_pickers.dart';
import 'profile_repository.dart';

/// Lengkapi profil setelah akun dibuat: gender → alias (sesuai gender) → avatar → Feed.
///
/// Sesi baru diberi tahu di langkah terakhir: server sudah menandai `onboarded` setelah alias dipilih,
/// dan kalau sesi diperbarui saat itu router langsung pindah ke Feed sebelum avatar sempat dipilih.
class CompleteProfileScreen extends ConsumerStatefulWidget {
  const CompleteProfileScreen({super.key});

  static const steps = 3;

  @override
  ConsumerState<CompleteProfileScreen> createState() => _CompleteProfileScreenState();
}

class _CompleteProfileScreenState extends ConsumerState<CompleteProfileScreen> {
  final _aliasKey = GlobalKey<AliasEditorState>();
  int _step = 0;
  Gender? _gender;
  Profile? _profile;
  AvatarOption? _avatar;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _profile = ref.read(sessionProvider).profile;
    _avatar = _profile?.avatar;
    final g = _profile?.gender;
    // Akun lama yang belum pernah memilih: biarkan kosong supaya user memilih sendiri.
    if (g != null && g != Gender.unspecified) _gender = g;
  }

  ProfileRepository get _repo => ref.read(profileRepositoryProvider);

  Future<void> _next() async {
    setState(() => _busy = true);
    try {
      switch (_step) {
        case 0:
          _profile = await _repo.setGender(_gender!);
          if (mounted) setState(() => _step = 1);
        case 1:
          final saved = await _aliasKey.currentState?.save();
          if (saved == null) return;
          _profile = saved;
          if (mounted) setState(() => _step = 2);
        case 2:
          var profile = _profile!;
          if (_avatar?.id != profile.avatar?.id) profile = await _repo.setAvatar(_avatar?.id);
          ref.read(sessionProvider.notifier).profileUpdated(profile);
      }
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final (title, subtitle) = switch (_step) {
      0 => ('Kamu siapa?', 'Gender tampil sebagai ikon kecil di samping aliasmu, dan menentukan nuansa alias. '
          'Boleh tidak disebut.'),
      1 => ('Pilih aliasmu', 'Nama inilah yang dilihat orang di post, balasan, dan Bisik. '
          'Setelah dipilih, alias hanya bisa diganti seminggu sekali.'),
      _ => ('Pilih avatar', 'Ilustrasi kecil, bukan foto. Bisa diganti kapan saja di Profil.'),
    };
    final canNext = !_busy && (_step != 0 || _gender != null);
    final profile = _profile;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && _step > 0 && !_busy) setState(() => _step--);
      },
      child: OnboardingScaffold(
        showBack: false,
        bar: SenyapBar(
          transparent: true,
          showBack: _step > 0,
          onBack: _busy ? () {} : () => setState(() => _step--),
          titleWidget: Text('Langkah ${_step + 1} dari ${CompleteProfileScreen.steps}',
              key: const Key('profile-step-label'),
              style: context.text.labelMedium?.copyWith(color: c.textSecondary)),
          actions: [
            TextButton(
              onPressed: () => ref.read(sessionProvider.notifier).logout(),
              style: TextButton.styleFrom(foregroundColor: c.textSecondary),
              child: const Text('Keluar'),
            ),
          ],
        ),
        top: _StepProgress(step: _step, total: CompleteProfileScreen.steps),
        title: title,
        subtitle: subtitle,
        primaryLabel: _step == 2 ? 'Selesai' : 'Lanjut',
        busy: _busy,
        onPrimary: canNext ? _next : null,
        children: [
          switch (_step) {
            0 => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                GenderPicker(value: _gender, onChanged: (g) => setState(() => _gender = g)),
                if (profile != null) ...[
                  const SizedBox(height: Space.md),
                  _IdentityPreview(profile: profile, gender: _gender ?? Gender.unspecified, emoji: _avatar?.emoji),
                ],
              ]),
            1 => AliasEditor(
                key: _aliasKey,
                gender: _gender ?? Gender.unspecified,
                showSaveButton: false,
                emoji: _avatar?.emoji,
                onSaved: (_) {},
              ),
            _ => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Container(
                  padding: const EdgeInsets.symmetric(vertical: Space.xl),
                  decoration: BoxDecoration(color: c.raised, borderRadius: Radii.all(Radii.lg)),
                  child: Column(children: [
                    AnimatedSwitcher(
                      duration: Motion.base,
                      transitionBuilder: (child, a) => ScaleTransition(
                        scale: Tween(begin: 0.85, end: 1.0).animate(CurvedAnimation(parent: a, curve: Motion.curve)),
                        child: FadeTransition(opacity: a, child: child),
                      ),
                      child: AliasAvatar(
                        key: ValueKey(_avatar?.id),
                        alias: profile!.alias,
                        emoji: _avatar?.emoji,
                        radius: 44,
                      ),
                    ),
                    const SizedBox(height: Space.md),
                    AliasName(
                      text: profile.alias.display,
                      gender: _gender ?? Gender.unspecified,
                      style: context.text.headlineSmall,
                    ),
                  ]),
                ),
                const SizedBox(height: Space.xl),
                AvatarPicker(
                  gender: _gender ?? Gender.unspecified,
                  alias: profile.alias,
                  selectedId: _avatar?.id,
                  onChanged: (a) => setState(() => _avatar = a),
                ),
              ]),
          },
        ],
      ),
    );
  }
}

/// Progres langkah: garis tipis bersegmen; segmen yang sudah dilewati berwarna tinta.
class _StepProgress extends StatelessWidget {
  const _StepProgress({required this.step, required this.total});

  final int step;
  final int total;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Semantics(
      key: const Key('profile-step-progress'),
      value: '${step + 1}/$total',
      child: Row(children: [
        for (var i = 0; i < total; i++) ...[
          if (i > 0) const SizedBox(width: 4),
          Expanded(
            child: AnimatedContainer(
              duration: Motion.base,
              curve: Motion.curve,
              height: 3,
              decoration: BoxDecoration(color: i <= step ? c.text : c.line, borderRadius: Radii.all(2)),
            ),
          ),
        ],
      ]),
    );
  }
}

/// Pratinjau kecil "begini kamu tampil" yang ikut berubah saat gender dipilih.
class _IdentityPreview extends StatelessWidget {
  const _IdentityPreview({required this.profile, required this.gender, this.emoji});

  final Profile profile;
  final Gender gender;
  final String? emoji;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: Space.md, vertical: Space.sm),
      decoration: BoxDecoration(
        borderRadius: Radii.all(Radii.md),
        border: Border.all(color: c.line, width: 1),
      ),
      child: Row(children: [
        Icon(Icons.visibility_outlined, size: 16, color: c.textTertiary),
        const SizedBox(width: Space.xs),
        Text('Tampil sebagai', style: context.text.labelSmall?.copyWith(color: c.textTertiary)),
        const SizedBox(width: Space.sm),
        Expanded(
          child: Row(mainAxisAlignment: MainAxisAlignment.end, children: [
            IdentityAvatar(name: '?', colorHex: profile.alias.color, emoji: emoji, radius: 13),
            const SizedBox(width: Space.xs),
            Flexible(
              child: Text('Alias pilihanmu',
                  maxLines: 1, overflow: TextOverflow.ellipsis, style: context.text.labelMedium),
            ),
            AnimatedSwitcher(
              duration: Motion.fast,
              child: gender == Gender.unspecified
                  ? const SizedBox.shrink()
                  : Padding(
                      key: ValueKey(gender),
                      padding: const EdgeInsets.only(left: 4),
                      child: GenderMark(gender, size: 15),
                    ),
            ),
          ]),
        ),
      ]),
    );
  }
}
