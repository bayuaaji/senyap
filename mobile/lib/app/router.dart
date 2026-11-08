import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/compose/compose_screen.dart';
import '../features/feed/feed_screen.dart';
import '../features/messages/messages_screen.dart';
import '../features/notifications/notifications_screen.dart';
import '../features/settings/settings_screen.dart';
import 'shell.dart';

/// Path tiap tab. Urutannya sama dengan urutan tab di [AppShell].
abstract final class AppRoutes {
  static const feed = '/feed';
  static const compose = '/tulis';
  static const messages = '/pesan';
  static const notifications = '/notifikasi';
  static const settings = '/pengaturan';
}

final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    initialLocation: AppRoutes.feed,
    routes: [
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => AppShell(shell: shell),
        branches: [
          _branch(AppRoutes.feed, const FeedScreen()),
          _branch(AppRoutes.compose, const ComposeScreen()),
          _branch(AppRoutes.messages, const MessagesScreen()),
          _branch(AppRoutes.notifications, const NotificationsScreen()),
          _branch(AppRoutes.settings, const SettingsScreen()),
        ],
      ),
    ],
  );
  ref.onDispose(router.dispose);
  return router;
});

StatefulShellBranch _branch(String path, Widget screen) => StatefulShellBranch(
      routes: [GoRoute(path: path, builder: (context, state) => screen)],
    );
