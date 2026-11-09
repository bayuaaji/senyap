import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'router.dart';
import 'theme/theme.dart';

class SenyapApp extends ConsumerWidget {
  const SenyapApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp.router(
      title: 'Senyap',
      debugShowCheckedModeBanner: false,
      theme: buildSenyapTheme(Brightness.light),
      darkTheme: buildSenyapTheme(Brightness.dark),
      // Mengikuti tema sistem; "malam" (gelap) dan "kertas" (terang) setara (DESIGN.md).
      themeMode: ThemeMode.system,
      routerConfig: ref.watch(routerProvider),
    );
  }
}
