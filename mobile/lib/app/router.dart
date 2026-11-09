import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/auth/session.dart';
import '../features/compose/compose_screen.dart';
import '../features/feed/feed_screen.dart';
import '../features/messages/chat_screen.dart';
import '../features/messages/messages_screen.dart';
import '../features/messages/new_whisper_screen.dart';
import '../features/notifications/notifications_screen.dart';
import '../features/onboarding/email_login_screen.dart';
import '../features/onboarding/email_register_screen.dart';
import '../features/onboarding/forgot_password_screen.dart';
import '../features/onboarding/otp_screen.dart';
import '../features/onboarding/phone_screen.dart';
import '../features/onboarding/register_screen.dart';
import '../features/onboarding/welcome_screen.dart';
import '../features/posts/post_detail_screen.dart';
import '../features/posts/post_models.dart';
import '../features/profile/complete_profile_screen.dart';
import '../features/settings/settings_screen.dart';
import 'shell.dart';
import 'ui/ui.dart';

/// Path tiap layar. Urutan tab sama dengan urutan di [AppShell].
abstract final class AppRoutes {
  static const splash = '/';
  /// Semua layar sebelum login ada di bawah `/mulai`.
  static const welcome = '/mulai';
  static const register = '/mulai/daftar';
  static const registerEmail = '/mulai/daftar/email';
  static const registerPhone = '/mulai/daftar/nomor';
  static const login = '/mulai/masuk';
  static const loginPhone = '/mulai/masuk/nomor';
  static const forgotPassword = '/mulai/masuk/lupa';
  static const otp = '/mulai/kode';

  /// Login tetapi belum onboarding: gender → alias → avatar.
  static const completeProfile = '/lengkapi-profil';

  static const feed = '/feed';
  static const compose = '/tulis';
  static const messages = '/pesan';
  static const notifications = '/notifikasi';
  static const settings = '/pengaturan';

  /// Mulai Bisik: `/bisik/baru?origin_type=POST&origin_id=…&excerpt=…`.
  static const newWhisper = '/bisik/baru';

  /// Post + balasan, di atas tab (tanpa bar navigasi bawah). `extra` boleh berisi [Post] untuk tampil instan.
  static const post = '/post/:id';
}

/// Arah layar berdasarkan status sesi. Dipisah supaya bisa diuji tanpa widget.
String? sessionRedirect(SessionStatus status, String location) {
  final inOnboarding = location.startsWith(AppRoutes.welcome);
  return switch (status) {
    SessionStatus.loading =>
      location == AppRoutes.splash ? null : AppRoutes.splash,
    SessionStatus.signedOut => inOnboarding ? null : AppRoutes.welcome,
    SessionStatus.needsProfile =>
      location == AppRoutes.completeProfile ? null : AppRoutes.completeProfile,
    SessionStatus.signedIn =>
      inOnboarding ||
              location == AppRoutes.splash ||
              location == AppRoutes.completeProfile
          ? AppRoutes.feed
          : null,
  };
}

final routerProvider = Provider<GoRouter>((ref) {
  final status = ValueNotifier(ref.read(sessionProvider).status);
  ref.listen(sessionProvider, (_, next) => status.value = next.status);

  final router = GoRouter(
    initialLocation: AppRoutes.splash,
    refreshListenable: status,
    redirect: (context, state) =>
        sessionRedirect(status.value, state.matchedLocation),
    routes: [
      GoRoute(
        path: AppRoutes.splash,
        builder: (context, state) => const _Splash(),
      ),
      GoRoute(
        path: AppRoutes.welcome,
        builder: (context, state) => const WelcomeScreen(),
        routes: [
          GoRoute(
            path: 'daftar',
            builder: (context, state) => const RegisterScreen(),
            routes: [
              GoRoute(path: 'email', builder: (context, state) => const EmailRegisterScreen()),
              GoRoute(path: 'nomor', builder: (context, state) => const PhoneScreen(register: true)),
            ],
          ),
          GoRoute(
            path: 'masuk',
            builder: (context, state) => const EmailLoginScreen(),
            routes: [
              GoRoute(path: 'nomor', builder: (context, state) => const PhoneScreen(register: false)),
              GoRoute(
                path: 'lupa',
                builder: (context, state) =>
                    ForgotPasswordScreen(initialEmail: state.extra is String ? state.extra as String : null),
              ),
            ],
          ),
          GoRoute(path: 'kode', builder: (context, state) => const OtpScreen()),
        ],
      ),
      GoRoute(
        path: AppRoutes.completeProfile,
        builder: (context, state) => const CompleteProfileScreen(),
      ),
      GoRoute(
        path: AppRoutes.newWhisper,
        builder: (context, state) => NewWhisperScreen(
          originType: state.uri.queryParameters['origin_type'] ?? 'POST',
          originId: state.uri.queryParameters['origin_id'] ?? '',
          excerpt: state.uri.queryParameters['excerpt'],
        ),
      ),
      GoRoute(
        path: AppRoutes.post,
        builder: (context, state) => PostDetailScreen(
          postId: state.pathParameters['id']!,
          initial: state.extra is Post ? state.extra as Post : null,
        ),
      ),
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => AppShell(shell: shell, location: state.uri.path),
        branches: [
          _branch(AppRoutes.feed, const FeedScreen()),
          _branch(AppRoutes.compose, const ComposeScreen()),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: AppRoutes.messages,
                builder: (context, state) => const MessagesScreen(),
                routes: [
                  GoRoute(
                    path: ':id',
                    builder: (context, state) =>
                        ChatScreen(conversationId: state.pathParameters['id']!),
                  ),
                ],
              ),
            ],
          ),
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
  Widget build(BuildContext context) => Scaffold(
        body: Center(child: Text('Senyap', style: context.text.displaySmall)),
      );
}
