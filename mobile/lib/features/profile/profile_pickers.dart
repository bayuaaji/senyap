import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/ui/ui.dart';
import 'alias_avatar.dart';
import 'profile_models.dart';
import 'profile_repository.dart';

const _genderIcons = {
  Gender.male: Icons.male_rounded,
  Gender.female: Icons.female_rounded,
  Gender.unspecified: Icons.remove_rounded,
};

const _genderHints = {
  Gender.male: 'Alias bernuansa laki-laki, mis. "Pangeran Senja".',
  Gender.female: 'Alias bernuansa perempuan, mis. "Putri Rembulan".',
  Gender.unspecified: 'Alias hewan dan warna. Tanpa ikon gender.',
};

/// Pilihan gender: baris radio (onboarding) atau [compact] berupa segmen (halaman Profil).
class GenderPicker extends StatelessWidget {
  const GenderPicker({super.key, required this.value, required this.onChanged, this.compact = false});

  final Gender? value;
  final ValueChanged<Gender>? onChanged;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    if (compact) {
      return Container(
        padding: const EdgeInsets.all(3),
        decoration: BoxDecoration(color: c.raised, borderRadius: Radii.all(Radii.md)),
        child: Row(children: [
          for (final g in Gender.values)
            Expanded(
              child: Semantics(
                selected: g == value,
                button: true,
                child: GestureDetector(
                  key: Key('gender-option-${g.wire}'),
                  behavior: HitTestBehavior.opaque,
                  onTap: onChanged == null || g == value
                      ? null
                      : () {
                          HapticFeedback.selectionClick();
                          onChanged!(g);
                        },
                  child: AnimatedContainer(
                    duration: Motion.fast,
                    curve: Motion.curve,
                    height: 42,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: g == value ? c.bg : Colors.transparent,
                      borderRadius: Radii.all(Radii.md - 3),
                    ),
                    child: Row(mainAxisSize: MainAxisSize.min, children: [
                      if (g != Gender.unspecified) ...[
                        Icon(_genderIcons[g], size: 16, color: g == value ? c.text : c.textTertiary),
                        const SizedBox(width: 4),
                      ],
                      Flexible(
                        child: Text(g.label,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: context.text.labelMedium?.copyWith(color: g == value ? c.text : c.textSecondary)),
                      ),
                    ]),
                  ),
                ),
              ),
            ),
        ]),
      );
    }
    return Column(children: [
      for (final g in Gender.values)
        Padding(
          padding: const EdgeInsets.only(bottom: Space.xs),
          child: Semantics(
            selected: g == value,
            inMutuallyExclusiveGroup: true,
            child: Pressable(
              key: Key('gender-option-${g.wire}'),
              haptic: true,
              onTap: onChanged == null ? null : () => onChanged!(g),
              child: AnimatedContainer(
                duration: Motion.fast,
                curve: Motion.curve,
                padding: const EdgeInsets.fromLTRB(Space.md, Space.md, Space.md, Space.md),
                decoration: BoxDecoration(
                  color: c.raised,
                  borderRadius: Radii.all(Radii.md),
                  border: Border.all(color: g == value ? c.text : Colors.transparent, width: 1.4),
                ),
                child: Row(children: [
                  Icon(_genderIcons[g], color: g == value ? c.text : c.textSecondary, size: 22),
                  const SizedBox(width: Space.md),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(g.label, style: context.text.titleMedium),
                      const SizedBox(height: 2),
                      Text(_genderHints[g]!, style: context.text.bodySmall?.copyWith(fontWeight: FontWeight.w400)),
                    ]),
                  ),
                  const SizedBox(width: Space.sm),
                  AnimatedContainer(
                    duration: Motion.fast,
                    width: 22,
                    height: 22,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(color: g == value ? c.text : c.textTertiary, width: g == value ? 7 : 1.6),
                    ),
                  ),
                ]),
              ),
            ),
          ),
        ),
    ]);
  }
}

/// Grid avatar ilustrasi dari katalog [gender]. Kotak pertama "Inisial" = tanpa avatar (`null`).
class AvatarPicker extends ConsumerStatefulWidget {
  const AvatarPicker({
    super.key,
    required this.gender,
    required this.alias,
    required this.selectedId,
    required this.onChanged,
  });

  final Gender gender;
  final Alias alias;
  final String? selectedId;
  final ValueChanged<AvatarOption?>? onChanged;

  @override
  ConsumerState<AvatarPicker> createState() => _AvatarPickerState();
}

class _AvatarPickerState extends ConsumerState<AvatarPicker> {
  List<AvatarOption>? _options;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(AvatarPicker old) {
    super.didUpdateWidget(old);
    if (old.gender != widget.gender) _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final c = await ref.read(profileRepositoryProvider).catalog(widget.gender);
      if (mounted) setState(() => _options = c.avatars);
    } catch (_) {
      if (mounted) setState(() => _error = 'Daftar avatar tidak bisa dimuat.');
    }
  }

  @override
  Widget build(BuildContext context) {
    final options = _options;
    if (_error != null) {
      return Center(child: TextButton(onPressed: _load, child: Text('$_error Coba lagi')));
    }
    if (options == null) {
      return const SizedBox(height: 160, child: Loader());
    }
    return GridView.count(
      crossAxisCount: 4,
      shrinkWrap: true,
      padding: EdgeInsets.zero,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: Space.sm,
      crossAxisSpacing: Space.sm,
      children: [
        _tile(context, null, AliasAvatar(alias: widget.alias, radius: 26)),
        for (final o in options) _tile(context, o, AliasAvatar(alias: widget.alias, emoji: o.emoji, radius: 26)),
      ],
    );
  }

  Widget _tile(BuildContext context, AvatarOption? option, Widget avatar) {
    final c = context.colors;
    final selected = option?.id == widget.selectedId;
    return Pressable(
      key: Key('avatar-option-${option?.id ?? 'none'}'),
      scale: 0.92,
      onTap: widget.onChanged == null
          ? null
          : () {
              HapticFeedback.selectionClick();
              widget.onChanged!(option);
            },
      child: AnimatedContainer(
        duration: Motion.base,
        curve: Motion.curve,
        decoration: BoxDecoration(
          color: c.raised,
          borderRadius: Radii.all(Radii.lg),
          border: Border.all(color: selected ? c.text : Colors.transparent, width: 2),
        ),
        child: Center(child: avatar),
      ),
    );
  }
}
