import 'dart:async';

import 'package:flutter/widgets.dart';

/// Waktu relatif (functional spec §4.1). Server mengirim `created_at` dibulatkan ke menit; client yang
/// memformat. Angka dibulatkan ke bawah, 1 bulan = 30 hari, 1 tahun = 365 hari, selisih negatif = "baru saja".
String formatRelative(DateTime createdAt, DateTime now) {
  final diff = now.difference(createdAt);
  if (diff.inMinutes < 1) return 'baru saja';
  if (diff.inMinutes < 60) return '${diff.inMinutes} menit yang lalu';
  if (diff.inHours < 24) return '${diff.inHours} jam yang lalu';
  final days = diff.inDays;
  if (days < 30) return '$days hari yang lalu';
  if (days < 365) return '${(days ~/ 30).clamp(1, 11)} bulan yang lalu';
  return '${days ~/ 365} tahun yang lalu';
}

/// Seberapa sering teks perlu diperbarui: tiap menit selama < 1 jam, lalu tiap jam.
Duration relativeRefreshInterval(DateTime createdAt, DateTime now) =>
    now.difference(createdAt) < const Duration(hours: 1) ? const Duration(minutes: 1) : const Duration(hours: 1);

/// Teks "x menit yang lalu" yang memperbarui dirinya selama layar terbuka.
class RelativeTimeText extends StatefulWidget {
  const RelativeTimeText(this.createdAt, {super.key, this.style, this.clock = DateTime.now});

  final DateTime createdAt;
  final TextStyle? style;
  final DateTime Function() clock;

  @override
  State<RelativeTimeText> createState() => _RelativeTimeTextState();
}

class _RelativeTimeTextState extends State<RelativeTimeText> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  @override
  void didUpdateWidget(RelativeTimeText old) {
    super.didUpdateWidget(old);
    if (old.createdAt != widget.createdAt) _schedule();
  }

  void _schedule() {
    _timer?.cancel();
    _timer = Timer(relativeRefreshInterval(widget.createdAt, widget.clock()), () {
      if (!mounted) return;
      setState(() {});
      _schedule();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Text(formatRelative(widget.createdAt, widget.clock()), style: widget.style);
}
