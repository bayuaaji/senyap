import 'package:flutter/material.dart';

import '../../app/placeholder_screen.dart';

class ComposeScreen extends StatelessWidget {
  const ComposeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      title: 'Tulis',
      message: 'Ceritakan sesuatu tanpa nama.',
      icon: Icons.edit,
    );
  }
}
