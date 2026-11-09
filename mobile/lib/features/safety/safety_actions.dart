import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import 'safety_repository.dart';

/// Menu "Laporkan / Blokir" untuk konten orang lain (post, balasan, percakapan, pesan).
Future<void> showSafetyMenu(BuildContext context, WidgetRef ref, SafetyTarget type, String targetId,
    {int? seq, VoidCallback? onBlocked}) async {
  final choice = await showSenyapSheet<String>(
    context,
    builder: (context) => Column(mainAxisSize: MainAxisSize.min, children: [
      SheetAction(
        key: const Key('safety-report'),
        icon: Icons.flag_outlined,
        title: 'Laporkan',
        subtitle: 'Tim kami meninjau tanpa memberi tahu dia siapa pelapornya.',
        onTap: () => Navigator.pop(context, 'report'),
      ),
      SheetAction(
        key: const Key('safety-block'),
        icon: Icons.block_rounded,
        danger: true,
        title: 'Blokir akun ini',
        subtitle: 'Kalian tidak akan saling melihat konten, dan dia tidak bisa berbisik kepadamu.',
        onTap: () => Navigator.pop(context, 'block'),
      ),
    ]),
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
  final reason = await showSenyapSheet<String>(
    context,
    scrollable: true,
    builder: (context) => ConstrainedBox(
      constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.8),
      child: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const SheetHeader('Kenapa kamu melaporkan ini?', subtitle: 'Pilih yang paling sesuai. Laporanmu anonim.'),
          for (final e in reportReasons.entries)
            InkWell(
              key: Key('reason-${e.key}'),
              onTap: () => Navigator.pop(context, e.key),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(Space.gutter, 16, Space.md, 16),
                child: Row(children: [
                  Expanded(child: Text(e.value, style: context.text.bodyLarge)),
                  Icon(Icons.chevron_right_rounded, color: context.colors.textTertiary),
                ]),
              ),
            ),
        ]),
      ),
    ),
  );
  if (!context.mounted || reason == null) return;
  try {
    await ref.read(safetyRepositoryProvider).report(type, targetId, reason, seq: seq);
  } on ApiError catch (e) {
    if (context.mounted) showToast(context, e.message, tone: ToastTone.error);
    return;
  }
  if (!context.mounted) return;
  final block = await showConfirm(
    context,
    icon: Icons.volunteer_activism_outlined,
    title: 'Terima kasih, laporanmu sudah kami terima',
    message: 'Tim kami akan meninjaunya. Mau sekalian memblokir akun ini?',
    cancelLabel: 'Tidak',
    confirmLabel: 'Blokir',
    destructive: true,
    confirmKey: const Key('report-then-block'),
  );
  if (block && context.mounted) {
    await _block(context, ref, type, targetId, seq: seq, onBlocked: onBlocked);
  }
}

Future<void> confirmBlock(BuildContext context, WidgetRef ref, SafetyTarget type, String targetId,
    {int? seq, VoidCallback? onBlocked}) async {
  final ok = await showConfirm(
    context,
    icon: Icons.block_rounded,
    title: 'Blokir akun ini?',
    message: 'Kalian tidak akan saling melihat post dan balasan, percakapan Bisik ditutup, dan dia tidak bisa '
        'berbisik kepadamu lagi. Dia tidak diberi tahu.',
    confirmLabel: 'Blokir',
    destructive: true,
    confirmKey: const Key('confirm-block'),
  );
  if (ok && context.mounted) {
    await _block(context, ref, type, targetId, seq: seq, onBlocked: onBlocked);
  }
}

Future<void> _block(BuildContext context, WidgetRef ref, SafetyTarget type, String targetId,
    {int? seq, VoidCallback? onBlocked}) async {
  try {
    await ref.read(safetyRepositoryProvider).block(type, targetId, seq: seq);
    if (context.mounted) showToast(context, 'Akun diblokir.', tone: ToastTone.success);
    onBlocked?.call();
  } on ApiError catch (e) {
    if (context.mounted) showToast(context, e.message, tone: ToastTone.error);
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
    builder: (context) {
      final c = context.colors;
      return SenyapDialog(
        key: const Key('support-card'),
        icon: Icons.favorite_outline_rounded,
        title: card?.title ?? 'Kamu tidak sendirian',
        message: card?.message ?? 'Bicara dengan orang yang kamu percaya bisa membantu.',
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (card != null && !card.verified)
            Container(
              key: const Key('support-unverified'),
              margin: const EdgeInsets.only(bottom: Space.sm),
              padding: const EdgeInsets.all(Space.sm),
              decoration: BoxDecoration(color: c.raised, borderRadius: Radii.all(Radii.sm)),
              child: Text('[PERLU DICEK SEBELUM RILIS] Nomor layanan di bawah belum diverifikasi.',
                  style: context.text.bodySmall?.copyWith(color: c.text)),
            ),
          for (final h in card?.hotlines ?? const <Hotline>[])
            Container(
              margin: const EdgeInsets.only(bottom: Space.xs),
              padding: const EdgeInsets.all(Space.md),
              decoration: BoxDecoration(color: c.raised, borderRadius: Radii.all(Radii.md)),
              child: Row(children: [
                Icon(Icons.phone_in_talk_outlined, size: 20, color: c.textSecondary),
                const SizedBox(width: Space.sm),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(h.name, style: context.text.titleSmall),
                    const SizedBox(height: 2),
                    Text([h.contact, ?h.hours].join(' · '), style: context.text.bodySmall),
                  ]),
                ),
              ]),
            ),
        ]),
        actions: [
          FilledButton(onPressed: () => Navigator.pop(context), child: const Text('Tutup')),
        ],
      );
    },
  );
}
