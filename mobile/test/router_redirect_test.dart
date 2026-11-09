import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/app/router.dart';
import 'package:senyap/core/auth/session.dart';

void main() {
  test('pengalihan berdasarkan status sesi', () {
    // Memuat sesi: selalu splash.
    expect(sessionRedirect(SessionStatus.loading, AppRoutes.feed), AppRoutes.splash);
    expect(sessionRedirect(SessionStatus.loading, AppRoutes.splash), isNull);

    // Belum login: hanya layar di bawah /mulai.
    expect(sessionRedirect(SessionStatus.signedOut, AppRoutes.feed), AppRoutes.welcome);
    expect(sessionRedirect(SessionStatus.signedOut, AppRoutes.splash), AppRoutes.welcome);
    expect(sessionRedirect(SessionStatus.signedOut, AppRoutes.completeProfile), AppRoutes.welcome);
    for (final r in [
      AppRoutes.welcome,
      AppRoutes.register,
      AppRoutes.registerEmail,
      AppRoutes.registerPhone,
      AppRoutes.login,
      AppRoutes.loginPhone,
      AppRoutes.forgotPassword,
      AppRoutes.otp,
    ]) {
      expect(sessionRedirect(SessionStatus.signedOut, r), isNull, reason: r);
    }

    // Login, belum onboarding: selalu Lengkapi profil.
    expect(sessionRedirect(SessionStatus.needsProfile, AppRoutes.feed), AppRoutes.completeProfile);
    expect(sessionRedirect(SessionStatus.needsProfile, AppRoutes.login), AppRoutes.completeProfile);
    expect(sessionRedirect(SessionStatus.needsProfile, AppRoutes.completeProfile), isNull);

    // Sudah onboarding: layar onboarding/splash → Feed, lainnya dibiarkan.
    expect(sessionRedirect(SessionStatus.signedIn, AppRoutes.otp), AppRoutes.feed);
    expect(sessionRedirect(SessionStatus.signedIn, AppRoutes.login), AppRoutes.feed);
    expect(sessionRedirect(SessionStatus.signedIn, AppRoutes.splash), AppRoutes.feed);
    expect(sessionRedirect(SessionStatus.signedIn, AppRoutes.completeProfile), AppRoutes.feed);
    expect(sessionRedirect(SessionStatus.signedIn, AppRoutes.settings), isNull);
    expect(sessionRedirect(SessionStatus.signedIn, '/pesan/123'), isNull);
  });
}
