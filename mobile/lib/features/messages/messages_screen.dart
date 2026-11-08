import 'package:flutter/material.dart';

import '../../app/placeholder_screen.dart';

class MessagesScreen extends StatelessWidget {
  const MessagesScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      title: 'Pesan',
      message: 'Bisikanmu akan muncul di sini.',
      icon: Icons.chat_bubble_outline,
    );
  }
}
