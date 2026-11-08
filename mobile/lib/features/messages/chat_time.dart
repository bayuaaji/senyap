/// Waktu relatif (functional spec §4.1).
///
/// TODO(merge Fase 3): pakai `core/time/relative_time.dart` (`formatRelative`) dari Fase 3 dan hapus file ini.
String chatRelative(DateTime createdAt, DateTime now) {
  final diff = now.difference(createdAt);
  if (diff.isNegative || diff.inMinutes < 1) return 'baru saja';
  if (diff.inMinutes < 60) return '${diff.inMinutes} menit yang lalu';
  if (diff.inHours < 24) return '${diff.inHours} jam yang lalu';
  if (diff.inDays < 30) return '${diff.inDays} hari yang lalu';
  if (diff.inDays < 365) return '${diff.inDays ~/ 30} bulan yang lalu';
  return '${diff.inDays ~/ 365} tahun yang lalu';
}
