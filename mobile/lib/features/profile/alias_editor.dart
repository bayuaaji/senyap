import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_error.dart';
import '../onboarding/onboarding_scaffold.dart';
import 'alias_avatar.dart';
import 'profile_models.dart';
import 'profile_repository.dart';

/// Memilih alias: "Acak lagi" (acak penuh) atau pilih hewan/warna sendiri dari daftar (tag tetap diacak
/// server), lalu "Pakai alias ini". Teks bebas tidak diizinkan (functional spec §1.1).
class AliasEditor extends ConsumerStatefulWidget {
  const AliasEditor({super.key, required this.onSaved, this.initial});

  final ValueChanged<Profile> onSaved;

  /// Alias yang sedang dipakai, ditampilkan sampai user meminta saran.
  final Alias? initial;

  @override
  ConsumerState<AliasEditor> createState() => _AliasEditorState();
}

class _AliasEditorState extends ConsumerState<AliasEditor> {
  AliasSuggestion? _suggestion;
  AliasCatalog? _catalog;
  String? _animal;
  String? _color;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _shuffle();
    ref.read(profileRepositoryProvider).catalog().then((c) {
      if (mounted) setState(() => _catalog = c);
    }, onError: (_) {});
  }

  Future<void> _shuffle() async {
    setState(() => _busy = true);
    try {
      final s = await ref.read(profileRepositoryProvider).suggest(animal: _animal, color: _color);
      if (mounted) setState(() => _suggestion = s);
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _use() async {
    final s = _suggestion;
    if (s == null) return;
    setState(() => _busy = true);
    try {
      final profile = await ref.read(profileRepositoryProvider).useAlias(s.token);
      widget.onSaved(profile);
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
      // Token saran sekali pakai: minta saran baru.
      if (e.code == 'VALIDATION_FAILED') await _shuffle();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final shown = _suggestion?.alias ?? widget.initial;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 8),
        if (shown != null) ...[
          Center(child: AliasAvatar(alias: shown, radius: 40)),
          const SizedBox(height: 12),
          Text(shown.display, key: const Key('alias-preview'), textAlign: TextAlign.center,
              style: theme.textTheme.titleLarge),
        ] else
          const Center(child: CircularProgressIndicator()),
        const SizedBox(height: 16),
        OutlinedButton.icon(
          onPressed: _busy ? null : _shuffle,
          icon: const Icon(Icons.casino_outlined),
          label: const Text('Acak lagi'),
        ),
        if (_catalog != null) ...[
          const SizedBox(height: 16),
          DropdownButtonFormField<String?>(
            initialValue: _animal,
            decoration: const InputDecoration(labelText: 'Hewan', border: OutlineInputBorder()),
            items: [
              const DropdownMenuItem(value: null, child: Text('Acak')),
              for (final a in _catalog!.animals) DropdownMenuItem(value: a, child: Text(a)),
            ],
            onChanged: _busy ? null : (v) {
              _animal = v;
              _shuffle();
            },
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<String?>(
            initialValue: _color,
            decoration: const InputDecoration(labelText: 'Warna', border: OutlineInputBorder()),
            items: [
              const DropdownMenuItem(value: null, child: Text('Acak')),
              for (final c in _catalog!.colors)
                DropdownMenuItem(
                  value: c.name,
                  child: Row(children: [
                    CircleAvatar(radius: 8, backgroundColor: aliasColor(c.hex)),
                    const SizedBox(width: 8),
                    Text(c.name),
                  ]),
                ),
            ],
            onChanged: _busy ? null : (v) {
              _color = v;
              _shuffle();
            },
          ),
        ],
        const SizedBox(height: 24),
        FilledButton(
          onPressed: _busy || _suggestion == null ? null : _use,
          child: const Text('Pakai alias ini'),
        ),
      ],
    );
  }
}
