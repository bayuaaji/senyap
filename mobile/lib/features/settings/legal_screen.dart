import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/ui/ui.dart';

/// Dokumen legal yang dibundel di `assets/legal/` (draf, perlu ditinjau konsultan hukum).
enum LegalDoc {
  terms('Syarat & Ketentuan', 'assets/legal/syarat-ketentuan.md'),
  privacy('Kebijakan Privasi', 'assets/legal/kebijakan-privasi.md'),
  guidelines('Pedoman Komunitas', 'assets/legal/pedoman-komunitas.md');

  const LegalDoc(this.title, this.asset);
  final String title;
  final String asset;
}

/// Pembaca Markdown sederhana (judul, daftar, kutipan, tebal) tanpa package tambahan.
class LegalScreen extends StatelessWidget {
  const LegalScreen({super.key, required this.doc, this.bundle});

  final LegalDoc doc;

  /// Untuk test; default [rootBundle].
  final AssetBundle? bundle;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: SenyapBar(title: doc.title),
      body: FutureBuilder<String>(
        future: (bundle ?? rootBundle).loadString(doc.asset),
        builder: (context, snap) {
          if (snap.hasError) return const Center(child: Text('Dokumen tidak bisa dibuka.'));
          if (!snap.hasData) return const Loader();
          return ListView(
            key: const Key('legal-body'),
            padding: const EdgeInsets.fromLTRB(Space.gutter, Space.md, Space.gutter, Space.x4),
            children: [for (final line in snap.data!.split('\n')) _line(context, line)],
          );
        },
      ),
    );
  }

  static Widget _line(BuildContext context, String raw) {
    final t = Theme.of(context).textTheme;
    final c = context.colors;
    final body = t.bodyMedium?.copyWith(color: c.textSecondary, height: 1.65);
    final line = raw.trimRight();
    if (line.isEmpty) return const SizedBox(height: Space.xs);
    if (line.startsWith('# ')) {
      return Padding(
        padding: const EdgeInsets.only(bottom: Space.md),
        child: _rich(line.substring(2), t.headlineMedium),
      );
    }
    if (line.startsWith('## ')) {
      return Padding(padding: const EdgeInsets.only(top: Space.lg, bottom: Space.xs), child: _rich(line.substring(3), t.headlineSmall));
    }
    if (line.startsWith('### ')) {
      return Padding(padding: const EdgeInsets.only(top: Space.sm, bottom: Space.xxs), child: _rich(line.substring(4), t.titleMedium));
    }
    if (line.startsWith('> ')) {
      return Container(
        margin: const EdgeInsets.symmetric(vertical: Space.xs),
        padding: const EdgeInsets.all(Space.md),
        decoration: BoxDecoration(color: c.raised, borderRadius: Radii.all(Radii.md)),
        child: _rich(line.substring(2), body?.copyWith(color: c.text)),
      );
    }
    final bullet = RegExp(r'^(\s*)[-*] ').firstMatch(line);
    if (bullet != null) {
      return Padding(
        padding: EdgeInsets.only(left: 4.0 + bullet.group(1)!.length * 6, bottom: Space.xxs),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.only(top: 10, right: Space.sm),
            child: Container(width: 4, height: 4, decoration: BoxDecoration(color: c.textTertiary, shape: BoxShape.circle)),
          ),
          Expanded(child: _rich(line.substring(bullet.end), body)),
        ]),
      );
    }
    return _rich(line, body);
  }

  /// `**tebal**` → bold; sisanya teks biasa.
  static Widget _rich(String text, TextStyle? style) {
    final spans = <TextSpan>[];
    final parts = text.split('**');
    for (var i = 0; i < parts.length; i++) {
      if (parts[i].isEmpty) continue;
      spans.add(TextSpan(text: parts[i], style: i.isOdd ? const TextStyle(fontWeight: FontWeight.w600) : null));
    }
    return Text.rich(TextSpan(style: style, children: spans));
  }
}
