import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/auth/token_store.dart';
import 'package:senyap/features/profile/profile_models.dart';

import 'posts_flow_test.dart' show usePhoneSize;
import 'support/fakes.dart';

void main() {
  testWidgets('Profil dari Pengaturan: ganti gender → katalog avatar ikut, pilih avatar, hapus avatar', (tester) async {
    usePhoneSize(tester);
    final profile = FakeProfileRepository();
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), profile: profile);

    await tester.tap(find.byKey(const Key('nav-settings')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('settings-profile')));
    await tester.pumpAndSettle();

    expect(find.text('Profil'), findsOneWidget);
    expect(find.text('Kelinci Jingga #4821'), findsWidgets);
    expect(find.byKey(const Key('gender-male')), findsNothing, reason: 'tidak disebut → tanpa ikon');
    expect(find.byKey(const Key('avatar-option-u-panda')), findsOneWidget);

    await tester.tap(find.byKey(const Key('gender-option-male')));
    await tester.pumpAndSettle();
    expect(profile.current.gender, Gender.male);
    expect(find.byKey(const Key('gender-male')), findsOneWidget);
    expect(find.byKey(const Key('avatar-option-m-singa')), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('avatar-option-m-singa')));
    await tester.tap(find.byKey(const Key('avatar-option-m-singa')));
    await tester.pumpAndSettle();
    expect(profile.current.avatar?.emoji, '🦁');
    expect(find.text('🦁'), findsWidgets);

    await tester.tap(find.byKey(const Key('avatar-option-none')));
    await tester.pumpAndSettle();
    expect(profile.current.avatar, isNull);

    // Ganti alias memakai katalog laki-laki
    await tester.tap(find.byKey(const Key('profile-change-alias')));
    await tester.pumpAndSettle();
    final preview = tester.widget<Text>(find.byKey(const Key('alias-preview'))).data!;
    expect(['Harimau', 'Elang', 'Serigala'].any(preview.startsWith), isTrue, reason: preview);
    await tester.tap(find.text('Pakai alias ini'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Bisa diganti lagi mulai'), findsOneWidget, reason: 'batas 7 hari');
    expect(find.text(preview), findsOneWidget);
  });
}
