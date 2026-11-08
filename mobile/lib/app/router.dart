import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/auth/session.dart';
import '../features/compose/compose_screen.dart';
import '../features/feed/feed_screen.dart';
import '../features/messages/messages_screen.dart';
import '../features/notifications/notifications_screen.dart';
import '../features/onboarding/age_screen.dart';
import '../features/onboarding/otp_screen.dart';
import '../features/onboarding/permissions_screen.dart';
import '../features/onboarding/phone_screen.dart';
import '../features/onboarding/welcome_screen.dart';
import '../features/posts/post_detail_screen.dart';
import '../features/posts/post_models.dart';
import '../features/profile/create_profile_screen.dart';
import '../features/settings/settings_screen.dart';
import 'shell.dart';

/// Path tiap layar. Urutan tab sama dengan urutan di [AppShell].
abstract final class AppRoutes {
  static const splash = '/';
  static const welcome = '/mulai';
  static const age = '/mulai/usia';
  static const phone = '/mulai/nomor';
  static const otp = '/mulai/kode';
  static const createProfile = '/profil-baru';
  static const permissions = '/izin';

  static const feed = '/feed';
  static const compose = '/tulis';
  static const messages = '/pesan';
  static const notifications = '/notifikasi';
  static const settings = '/pengaturan';

  /// Post + balasan, di atas tab (tanpa bar navigasi bawah). `extra` boleh berisi [Post] untuk tampil instan.
  static const post = '/post/:id';
}

/// Arah layar berdasarkan status sesi. Dipisah supaya bisa diuji tanpa widget.
String? sessionRedirect(SessionStatus status, String location) {
  final inOnboarding = location.startsWith(AppRoutes.welcome);
  return switch (status) {
    SessionStatus.loading => location == AppRoutes.splash ? null : AppRoutes.splash,
    SessionStatus.signedOut => inOnboarding ? null : AppRoutes.welcome,
    SessionStatus.needsProfile => location == AppRoutes.createProfile ? null : AppRoutes.createProfile,
    SessionStatus.signedIn =>
      inOnboarding || location == AppRoutes.splash || location == AppRoutes.createProfile ? AppRoutes.feed : null,
  };
}

final routerProvider = Provider<GoRouter>((ref) {
  final status = ValueNotifier(ref.read(sessionProvider).status);
  ref.listen(sessionProvider, (_, next) => status.value = next.status);

  final router = GoRouter(
    initialLocation: AppRoutes.splash,
    refreshListenable: status,
    redirect: (context, state) => sessionRedirect(status.value, state.matchedLocation),
    routes: [
      GoRoute(path: AppRoutes.splash, builder: (context, state) => const _Splash()),
      GoRoute(
        path: AppRoutes.welcome,
        builder: (context, state) => const WelcomeScreen(),
        routes: [
          GoRoute(path: 'usia', builder: (context, state) => const AgeScreen()),
          GoRoute(path: 'nomor', builder: (context, state) => const PhoneScreen()),
          GoRoute(path: 'kode', builder: (context, state) => const OtpScreen()),
        ],
      ),
      GoRoute(path: AppRoutes.createProfile, builder: (context, state) => const CreateProfileScreen()),
      GoRoute(path: AppRoutes.permissions, builder: (context, state) => const PermissionsScreen()),
      GoRoute(
        path: AppRoutes.post,
        builder: (context, state) => PostDetailScreen(
          postId: state.pathParameters['id']!,
          initial: state.extra is Post ? state.extra as Post : null,
        ),
      ),
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
  ref.onDispose(() {
    router.dispose();
    status.dispose();
  });
  return router;
});

StatefulShellBranch _branch(String path, Widget screen) => StatefulShellBranch(
      routes: [GoRoute(path: path, builder: (context, state) => screen)],
    );

class _Splash extends StatelessWidget {
  const _Splash();

  @override
  Widget build(BuildContext context) => const Scaffold(body: Center(child: CircularProgressIndicator()));
}
