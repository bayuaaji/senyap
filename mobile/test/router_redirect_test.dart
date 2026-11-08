import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/app/router.dart';
import 'package:senyap/core/auth/session.dart';

void main() {
  test('pengalihan berdasarkan status sesi', () {
    expect(sessionRedirect(SessionStatus.loading, AppRoutes.feed), AppRoutes.splash);
    expect(sessionRedirect(SessionStatus.signedOut, AppRoutes.feed), AppRoutes.welcome);
    expect(sessionRedirect(SessionStatus.signedOut, AppRoutes.otp), isNull);
    expect(sessionRedirect(SessionStatus.needsProfile, AppRoutes.feed), AppRoutes.createProfile);
    expect(sessionRedirect(SessionStatus.signedIn, AppRoutes.otp), AppRoutes.feed);
    expect(sessionRedirect(SessionStatus.signedIn, AppRoutes.permissions), isNull);
    expect(sessionRedirect(SessionStatus.signedIn, AppRoutes.settings), isNull);
  });
}
