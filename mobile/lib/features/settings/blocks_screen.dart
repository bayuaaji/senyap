import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../safety/safety_repository.dart';

/// Daftar akun yang kamu blokir. Membuka blokir tidak membuka lagi percakapan yang sudah ditutup.
class BlocksScreen extends ConsumerStatefulWidget {
  const BlocksScreen({super.key});

  @override
  ConsumerState<BlocksScreen> createState() => _BlocksScreenState();
}

class _BlocksScreenState extends ConsumerState<BlocksScreen> {
  List<BlockedAccount>? _items;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final items = await ref.read(safetyRepositoryProvider).blocks();
      if (!mounted) return;
      setState(() {
        _items = items;
        _error = null;
      });
    } on ApiError catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _unblock(BlockedAccount b) async {
    final ok = await showConfirm(
      context,
      icon: Icons.lock_open_rounded,
      title: 'Buka blokir ${b.display}?',
      message: 'Kalian bisa saling melihat konten lagi. Percakapan Bisik yang sudah ditutup tidak dibuka kembali.',
      confirmLabel: 'Buka blokir',
      confirmKey: const Key('confirm-unblock'),
    );
    if (!ok || !mounted) return;
    try {
      await ref.read(safetyRepositoryProvider).unblock(b.id);
      await _load();
    } on ApiError catch (e) {
      if (mounted) showToast(context, e.message, tone: ToastTone.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: const SenyapBar(),
      body: ListView(padding: const EdgeInsets.only(bottom: Space.x4), children: [
        const ScreenTitle('Akun yang diblokir',
            subtitle: 'Mereka tidak bisa melihat kontenmu atau berbisik kepadamu. Mereka tidak diberi tahu.',
            padding: EdgeInsets.fromLTRB(Space.gutter, Space.md, Space.gutter, Space.xl)),
        switch ((_items, _error)) {
          (_, final String error) => EmptyState(
              icon: Icons.cloud_off_rounded,
              title: 'Belum tersambung',
              message: error,
              action: SecondaryButton(label: 'Coba lagi', expand: false, onPressed: _load),
            ),
          (null, _) => const SizedBox(height: 240, child: ListSkeleton(count: 3)),
          (final List<BlockedAccount> items, _) when items.isEmpty => const Padding(
              padding: EdgeInsets.only(top: Space.xxl),
              child: EmptyState(
                title: 'Tidak ada yang diblokir',
                message: 'Belum ada akun yang kamu blokir.',
              ),
            ),
          (final List<BlockedAccount> items, _) => GroupCard(children: [
              for (final b in items)
                Padding(
                  key: Key('blocked-${b.id}'),
                  padding: const EdgeInsets.fromLTRB(Space.gutter, Space.xs, Space.sm, Space.xs),
                  child: Row(children: [
                    Expanded(child: Text(b.display, style: context.text.titleMedium)),
                    TextButton(onPressed: () => _unblock(b), child: const Text('Buka blokir')),
                  ]),
                ),
            ]),
        },
      ]),
    );
  }
}
