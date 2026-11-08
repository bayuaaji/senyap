import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'router.dart';

class SenyapApp extends ConsumerWidget {
  const SenyapApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp.router(
      title: 'Senyap',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF5B4B8A),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorSchemeSeed: const Color(0xFF5B4B8A),
        brightness: Brightness.dark,
        useMaterial3: true,
      ),
      routerConfig: ref.watch(routerProvider),
    );
  }
}
