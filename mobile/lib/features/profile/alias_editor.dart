import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../onboarding/onboarding_scaffold.dart';
import 'alias_avatar.dart';
import 'profile_models.dart';
import 'profile_repository.dart';

/// Memilih alias dari katalog sesuai [gender]: "Acak lagi" (acak penuh) atau "Pilih sendiri" kata pertama
/// dan kata kedua dari daftar (tag tetap diacak server). Teks bebas tidak diizinkan (functional spec §1.1).
///
/// Dengan [showSaveButton] `false`, induk memanggil [AliasEditorState.save] lewat `GlobalKey`.
class AliasEditor extends ConsumerStatefulWidget {
  const AliasEditor({
    super.key,
    required this.gender,
    required this.onSaved,
    this.initial,
    this.emoji,
    this.showSaveButton = true,
  });

  final Gender gender;
  final ValueChanged<Profile> onSaved;

  /// Alias yang sedang dipakai, ditampilkan sampai saran pertama datang.
  final Alias? initial;

  /// Emoji avatar untuk pratinjau.
  final String? emoji;
  final bool showSaveButton;

  @override
  ConsumerState<AliasEditor> createState() => AliasEditorState();
}

class AliasEditorState extends ConsumerState<AliasEditor> {
  AliasSuggestion? _suggestion;
  AliasCatalog? _catalog;
  String? _first;
  String? _second;
  bool _custom = false;
  bool _busy = false;

  bool get busy => _busy;

  @override
  void initState() {
    super.initState();
    _shuffle();
    ref.read(profileRepositoryProvider).catalog(widget.gender).then((c) {
      if (mounted) setState(() => _catalog = c);
    }, onError: (_) {});
  }

  Future<void> _suggest() async {
    setState(() => _busy = true);
    try {
      final s = await ref.read(profileRepositoryProvider).suggest(first: _first, second: _second);
      if (mounted) setState(() => _suggestion = s);
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _shuffle() {
    _first = null;
    _second = null;
    return _suggest();
  }

  /// Memakai alias yang sedang ditampilkan. `null` bila gagal atau belum ada saran.
  Future<Profile?> save() async {
    final s = _suggestion;
    if (s == null || _busy) return null;
    setState(() => _busy = true);
    try {
      final profile = await ref.read(profileRepositoryProvider).useAlias(s.token);
      widget.onSaved(profile);
      return profile;
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
      // Token saran sekali pakai: minta saran baru.
      if (e.code == 'VALIDATION_FAILED' && mounted) {
        setState(() => _busy = false);
        await _suggest();
      }
      return null;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final shown = _suggestion?.alias ?? widget.initial;
    final catalog = _catalog;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // Kartu identitas: pratinjau hidup alias + avatar.
        Container(
          height: 220,
          decoration: BoxDecoration(color: c.raised, borderRadius: Radii.all(Radii.lg)),
          child: Stack(alignment: Alignment.center, children: [
            if (shown == null)
              const Loader()
            else
              AnimatedSwitcher(
                duration: Motion.base,
                switchInCurve: Motion.curve,
                transitionBuilder: (child, a) => FadeTransition(
                  opacity: a,
                  child: SlideTransition(
                    position: Tween(begin: const Offset(0, 0.06), end: Offset.zero).animate(a),
                    child: child,
                  ),
                ),
                child: Column(key: ValueKey(shown.display), mainAxisSize: MainAxisSize.min, children: [
                  AliasAvatar(alias: shown, emoji: widget.emoji, radius: 40),
                  const SizedBox(height: Space.md),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: Space.md),
                    child: Row(mainAxisSize: MainAxisSize.min, children: [
                      Flexible(
                        child: Text(shown.display,
                            key: const Key('alias-preview'),
                            textAlign: TextAlign.center,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: context.text.headlineSmall),
                      ),
                      if (widget.gender != Gender.unspecified) ...[
                        const SizedBox(width: 6),
                        GenderMark(widget.gender, size: 20),
                      ],
                    ]),
                  ),
                  const SizedBox(height: Space.xxs),
                  Text('Begini orang lain melihatmu', style: context.text.bodySmall?.copyWith(color: c.textTertiary)),
                ]),
              ),
          ]),
        ),
        const SizedBox(height: Space.md),
        Row(children: [
          Expanded(
            child: SecondaryButton(
              key: const Key('alias-shuffle'),
              onPressed: _busy ? null : () {
                HapticFeedback.selectionClick();
                _shuffle();
              },
              icon: Icons.casino_outlined,
              label: 'Acak lagi',
            ),
          ),
          const SizedBox(width: Space.sm),
          Expanded(
            child: SecondaryButton(
              key: const Key('alias-custom'),
              onPressed: catalog == null ? null : () => setState(() => _custom = !_custom),
              icon: _custom ? Icons.expand_less_rounded : Icons.list_rounded,
              label: 'Pilih sendiri',
            ),
          ),
        ]),
        AnimatedSize(
          duration: Motion.base,
          curve: Motion.curve,
          alignment: Alignment.topCenter,
          child: !(_custom && catalog != null)
              ? const SizedBox(width: double.infinity)
              : Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  const SizedBox(height: Space.md),
                  DropdownButtonFormField<String?>(
                    key: const Key('alias-first'),
                    initialValue: _first,
                    isExpanded: true,
                    dropdownColor: c.raised,
                    borderRadius: Radii.all(Radii.md),
                    icon: const Icon(Icons.expand_more_rounded),
                    decoration: const InputDecoration(labelText: 'Kata pertama'),
                    items: [
                      const DropdownMenuItem(value: null, child: Text('Acak')),
                      for (final a in catalog.first) DropdownMenuItem(value: a, child: Text(a)),
                    ],
                    onChanged: _busy
                        ? null
                        : (v) {
                            _first = v;
                            _suggest();
                          },
                  ),
                  const SizedBox(height: Space.sm),
                  DropdownButtonFormField<String?>(
                    key: const Key('alias-second'),
                    initialValue: _second,
                    isExpanded: true,
                    dropdownColor: c.raised,
                    borderRadius: Radii.all(Radii.md),
                    icon: const Icon(Icons.expand_more_rounded),
                    decoration: const InputDecoration(labelText: 'Kata kedua'),
                    items: [
                      const DropdownMenuItem(value: null, child: Text('Acak')),
                      for (final col in catalog.second)
                        DropdownMenuItem(
                          value: col.name,
                          child: Row(children: [
                            Container(
                              width: 14,
                              height: 14,
                              decoration: BoxDecoration(color: aliasColor(col.hex), shape: BoxShape.circle),
                            ),
                            const SizedBox(width: Space.sm),
                            Text(col.name),
                          ]),
                        ),
                    ],
                    onChanged: _busy
                        ? null
                        : (v) {
                            _second = v;
                            _suggest();
                          },
                  ),
                ]),
        ),
        if (widget.showSaveButton) ...[
          const SizedBox(height: Space.xl),
          PrimaryButton(
            label: 'Pakai alias ini',
            onPressed: _busy || _suggestion == null ? null : save,
          ),
        ],
      ],
    );
  }
}
