import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';

/// Asal Bisik: post atau balasan (functional spec FS-05 §5.1). Nilai sama dengan `origin_type` di server.
enum WhisperOriginType { post, reply }

/// Titik masuk tombol **Bisik** di post dan balasan: membuka layar request Bisik
/// (`/bisik/baru?origin_type=…&origin_id=…&excerpt=…`). Server memvalidasi asal lewat `PostsApi.findOrigins`.
Future<void> openWhisper(
  BuildContext context, {
  required WhisperOriginType type,
  required String originId,
  String? excerpt,
}) {
  final params = <String, String>{
    'origin_type': type == WhisperOriginType.post ? 'POST' : 'REPLY',
    'origin_id': originId,
    if (excerpt != null && excerpt.isNotEmpty) 'excerpt': excerpt.length > 80 ? '${excerpt.substring(0, 79)}…' : excerpt,
  };
  return context.push(Uri(path: AppRoutes.newWhisper, queryParameters: params).toString());
}
