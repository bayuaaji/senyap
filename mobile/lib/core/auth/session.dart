import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/onboarding/auth_repository.dart';
import '../../features/profile/profile_models.dart';
import '../../features/profile/profile_repository.dart';
import '../api/api_client.dart';
import '../api/api_error.dart';

enum SessionStatus { loading, signedOut, needsProfile, signedIn }

class SessionState {
  const SessionState(this.status, [this.profile]);

  final SessionStatus status;
  final Profile? profile;
}

final sessionProvider = NotifierProvider<SessionController, SessionState>(SessionController.new);

/// Status login aplikasi. Router mengarahkan layar berdasarkan [SessionState.status].
class SessionController extends Notifier<SessionState> {
  @override
  SessionState build() {
    ref.listen(sessionExpiredProvider, (_, _) => state = const SessionState(SessionStatus.signedOut));
    Future.microtask(_restore);
    return const SessionState(SessionStatus.loading);
  }

  Future<void> _restore() async {
    final tokens = await ref.read(tokenStoreProvider).read();
    if (tokens == null) {
      state = const SessionState(SessionStatus.signedOut);
      return;
    }
    await reloadProfile();
  }

  Future<void> reloadProfile() async {
    try {
      final profile = await ref.read(profileRepositoryProvider).profile();
      _setProfile(profile);
    } on ApiError catch (e) {
      if (e.status == 401) {
        state = const SessionState(SessionStatus.signedOut);
      } else if (state.status == SessionStatus.loading) {
        // Offline saat start: tetap masuk; layar akan mencoba lagi saat butuh data.
        state = const SessionState(SessionStatus.signedIn);
      }
    }
  }

  /// Setelah OTP terverifikasi.
  Future<void> signedIn(VerifyResult result) async {
    await ref.read(tokenStoreProvider).save(result.tokens);
    await reloadProfile();
  }

  void profileUpdated(Profile profile) => _setProfile(profile);

  void _setProfile(Profile profile) {
    state = SessionState(profile.onboarded ? SessionStatus.signedIn : SessionStatus.needsProfile, profile);
  }

  Future<void> logout() async {
    try {
      await ref.read(authRepositoryProvider).logout();
    } on ApiError {
      // Tetap keluar secara lokal.
    }
    await ref.read(tokenStoreProvider).clear();
    state = const SessionState(SessionStatus.signedOut);
  }

  Future<void> deleteAccount() async {
    await ref.read(authRepositoryProvider).deleteAccount();
    await ref.read(tokenStoreProvider).clear();
    state = const SessionState(SessionStatus.signedOut);
  }
}
