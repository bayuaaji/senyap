import 'package:flutter/material.dart';

import '../../app/placeholder_screen.dart';

class FeedScreen extends StatelessWidget {
  const FeedScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      title: 'Feed',
      message: 'Belum ada cerita di sini.',
      icon: Icons.dynamic_feed,
    );
  }
}
