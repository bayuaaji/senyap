import 'package:flutter/material.dart';

import '../../app/placeholder_screen.dart';

class NotificationsScreen extends StatelessWidget {
  const NotificationsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      title: 'Notifikasi',
      message: 'Belum ada notifikasi.',
      icon: Icons.notifications_none,
    );
  }
}
