import 'package:flutter/material.dart';

import '../../app/placeholder_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      title: 'Pengaturan',
      message: 'Pengaturan akun dan alias.',
      icon: Icons.settings,
    );
  }
}
