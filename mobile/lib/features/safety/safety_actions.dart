import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_error.dart';
import 'safety_repository.dart';

/// Menu "Laporkan / Blokir" untuk konten orang lain (post, balasan, percakapan, pesan).
Future<void> showSafetyMenu(BuildContext context, WidgetRef ref, SafetyTarget type, String targetId,
    {int? seq, VoidCallback? onBlocked}) async {
  final choice = await showModalBottomSheet<String>(
    context: context,
    builder: (context) => SafeArea(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        ListTile(
          key: const Key('safety-report'),
          leading: const Icon(Icons.flag_outlined),
          title: const Text('Laporkan'),
          onTap: () => Navigator.pop(context, 'report'),
        ),
        ListTile(
          key: const Key('safety-block'),
          leading: const Icon(Icons.block),
          title: const Text('Blokir akun ini'),
          subtitle: const Text('Kalian tidak akan saling melihat konten, dan dia tidak bisa berbisik kepadamu.'),
          onTap: () => Navigator.pop(context, 'block'),
        ),
      ]),
    ),
  );
  if (!context.mounted || choice == null) return;
  if (choice == 'report') {
    await showReportSheet(context, ref, type, targetId, seq: seq, onBlocked: onBlocked);
  } else {
    await confirmBlock(context, ref, type, targetId, seq: seq, onBlocked: onBlocked);
  }
}

/// Pilih alasan → kirim laporan → tawarkan blokir. Pelapor nanti menerima notifikasi tanpa detail sanksi.
Future<void> showReportSheet(BuildContext context, WidgetRef ref, SafetyTarget type, String targetId,
    {int? seq, VoidCallback? onBlocked}) async {
  final reason = await showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text('Kenapa kamu melaporkan ini?', style: TextStyle(fontWeight: FontWeight.w600)),
          ),
          for (final e in reportReasons.entries)
            ListTile(key: Key('reason-${e.key}'), title: Text(e.value), onTap: () => Navigator.pop(context, e.key)),
        ]),
      ),
    ),
  );
  if (!context.mounted || reason == null) return;
  final messenger = ScaffoldMessenger.of(context);
  try {
    await ref.read(safetyRepositoryProvider).report(type, targetId, reason, seq: seq);
  } on ApiError catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(e.message)));
    return;
  }
  if (!context.mounted) return;
  final block = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('Terima kasih, laporanmu sudah kami terima'),
      content: const Text('Tim kami akan meninjaunya. Mau sekalian memblokir akun ini?'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Tidak')),
        FilledButton(key: const Key('report-then-block'), onPressed: () => Navigator.pop(context, true), child: const Text('Blokir')),
      ],
    ),
  );
  if (block == true && context.mounted) {
    await _block(context, ref, type, targetId, seq: seq, onBlocked: onBlocked);
  }
}

Future<void> confirmBlock(BuildContext context, WidgetRef ref, SafetyTarget type, String targetId,
    {int? seq, VoidCallback? onBlocked}) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('Blokir akun ini?'),
      content: const Text(
          'Kalian tidak akan saling melihat post dan balasan, percakapan Bisik ditutup, dan dia tidak bisa berbisik kepadamu lagi. Dia tidak diberi tahu.'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Batal')),
        FilledButton(key: const Key('confirm-block'), onPressed: () => Navigator.pop(context, true), child: const Text('Blokir')),
      ],
    ),
  );
  if (ok == true && context.mounted) {
    await _block(context, ref, type, targetId, seq: seq, onBlocked: onBlocked);
  }
}

Future<void> _block(BuildContext context, WidgetRef ref, SafetyTarget type, String targetId,
    {int? seq, VoidCallback? onBlocked}) async {
  final messenger = ScaffoldMessenger.of(context);
  try {
    await ref.read(safetyRepositoryProvider).block(type, targetId, seq: seq);
    messenger.showSnackBar(const SnackBar(content: Text('Akun diblokir.')));
    onBlocked?.call();
  } on ApiError catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(e.message)));
  }
}

/// Kartu bantuan risiko menyakiti diri (FS-08). Tidak memblokir konten; hanya menawarkan bantuan ke penulis.
Future<void> showSupportCard(BuildContext context, WidgetRef ref) async {
  SupportCard? card;
  try {
    card = await ref.read(safetyRepositoryProvider).supportCard();
  } on ApiError {
    card = null;
  }
  if (!context.mounted) return;
  await showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      key: const Key('support-card'),
      icon: const Icon(Icons.favorite_outline),
      title: Text(card?.title ?? 'Kamu tidak sendirian'),
      content: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(card?.message ?? 'Bicara dengan orang yang kamu percaya bisa membantu.'),
          if (card != null && !card.verified)
            Container(
              key: const Key('support-unverified'),
              margin: const EdgeInsets.only(top: 12),
              padding: const EdgeInsets.all(8),
              color: Colors.orange.withValues(alpha: 0.15),
              child: const Text('[PERLU DICEK SEBELUM RILIS] Nomor layanan di bawah belum diverifikasi.',
                  style: TextStyle(fontSize: 12)),
            ),
          for (final h in card?.hotlines ?? const <Hotline>[])
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.phone_outlined),
              title: Text(h.name),
              subtitle: Text([h.contact, ?h.hours].join(' · ')),
            ),
        ]),
      ),
      actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Tutup'))],
    ),
  );
}
