import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Buka blokir ${b.display}?'),
        content: const Text('Kalian bisa saling melihat konten lagi. Percakapan Bisik yang sudah ditutup tidak dibuka kembali.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Batal')),
          FilledButton(key: const Key('confirm-unblock'), onPressed: () => Navigator.pop(context, true), child: const Text('Buka blokir')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(safetyRepositoryProvider).unblock(b.id);
      await _load();
    } on ApiError catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Akun yang diblokir')),
      body: switch ((_items, _error)) {
        (_, final String error) => Center(child: Text(error)),
        (null, _) => const Center(child: CircularProgressIndicator()),
        (final List<BlockedAccount> items, _) when items.isEmpty =>
          const Center(child: Text('Belum ada akun yang kamu blokir.')),
        (final List<BlockedAccount> items, _) => ListView(children: [
            for (final b in items)
              ListTile(
                key: Key('blocked-${b.id}'),
                leading: const Icon(Icons.block),
                title: Text(b.display),
                trailing: TextButton(onPressed: () => _unblock(b), child: const Text('Buka blokir')),
              ),
          ]),
      },
    );
  }
}
