import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

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
      appBar: AppBar(title: Text(doc.title)),
      body: FutureBuilder<String>(
        future: (bundle ?? rootBundle).loadString(doc.asset),
        builder: (context, snap) {
          if (snap.hasError) return const Center(child: Text('Dokumen tidak bisa dibuka.'));
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          return ListView(
            key: const Key('legal-body'),
            padding: const EdgeInsets.all(16),
            children: [for (final line in snap.data!.split('\n')) _line(context, line)],
          );
        },
      ),
    );
  }

  static Widget _line(BuildContext context, String raw) {
    final t = Theme.of(context).textTheme;
    final line = raw.trimRight();
    if (line.isEmpty) return const SizedBox(height: 8);
    if (line.startsWith('# ')) return Padding(padding: const EdgeInsets.only(bottom: 8), child: _rich(line.substring(2), t.headlineSmall));
    if (line.startsWith('## ')) return Padding(padding: const EdgeInsets.only(top: 8, bottom: 4), child: _rich(line.substring(3), t.titleMedium));
    if (line.startsWith('### ')) return _rich(line.substring(4), t.titleSmall);
    if (line.startsWith('> ')) {
      return Container(
        padding: const EdgeInsets.all(8),
        color: Colors.orange.withValues(alpha: 0.12),
        child: _rich(line.substring(2), t.bodyMedium),
      );
    }
    final bullet = RegExp(r'^(\s*)[-*] ').firstMatch(line);
    if (bullet != null) {
      return Padding(
        padding: EdgeInsets.only(left: 8.0 + bullet.group(1)!.length * 6, bottom: 2),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('•  '),
          Expanded(child: _rich(line.substring(bullet.end), t.bodyMedium)),
        ]),
      );
    }
    return _rich(line, t.bodyMedium);
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
