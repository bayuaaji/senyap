import 'package:flutter/material.dart';

/// Asal Bisik: post atau balasan (functional spec FS-05 §5.1). Nilai sama dengan `origin_type` di server.
enum WhisperOriginType { post, reply }

/// Titik masuk tombol **Bisik** di post dan balasan.
///
/// TODO(Fase 2 — merge): ganti isi fungsi ini dengan navigasi ke layar request Bisik dari fitur chat,
/// misalnya `context.push('/bisik/baru?origin_type=POST&origin_id=…')`. Server memvalidasi asal lewat
/// `PostsApi.findOrigins`. Sampai digabung, tombol membuka lembar placeholder.
Future<void> openWhisper(BuildContext context, {required WhisperOriginType type, required String originId}) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    builder: (context) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Bisik', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(
              type == WhisperOriginType.post
                  ? 'Kirim pesan pribadi ke penulis post ini tanpa membuka identitasmu.'
                  : 'Kirim pesan pribadi ke penulis balasan ini tanpa membuka identitasmu.',
            ),
            const SizedBox(height: 12),
            const Text('Fitur Bisik segera hadir.', key: Key('whisper-placeholder')),
            const SizedBox(height: 16),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Tutup')),
            ),
          ],
        ),
      ),
    ),
  );
}
