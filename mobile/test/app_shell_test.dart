import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/auth/token_store.dart';

import 'support/fakes.dart';

void main() {
  testWidgets('sudah login: menampilkan 5 tab dan mulai di Feed', (tester) async {
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens));

    for (final label in ['Feed', 'Tulis', 'Pesan', 'Notifikasi', 'Pengaturan']) {
      expect(
        find.descendant(of: find.byKey(const Key('app-nav')), matching: find.text(label)),
        findsOneWidget,
      );
    }
    expect(find.text('Belum ada cerita di sini.'), findsOneWidget);
  });

  testWidgets('pindah tab ke Pesan lalu Pengaturan menampilkan alias', (tester) async {
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens));

    await tester.tap(find.byKey(const Key('nav-messages')));
    await tester.pumpAndSettle();
    expect(find.text('Belum ada bisikan'), findsOneWidget);

    await tester.tap(find.byKey(const Key('nav-settings')));
    await tester.pumpAndSettle();
    expect(find.text('Kelinci Jingga #4821'), findsOneWidget);
  });

  testWidgets('keluar menghapus token dan kembali ke layar pembuka', (tester) async {
    final tokens = InMemoryTokenStore(testTokens);
    final auth = FakeAuthRepository();
    await pumpApp(tester, tokens: tokens, auth: auth);

    await tester.tap(find.byKey(const Key('nav-settings')));
    await tester.pumpAndSettle();
    // Daftar pengaturan lebih lega sekarang: gulir sampai baris Keluar dibangun dan terlihat.
    await tester.scrollUntilVisible(find.text('Keluar'), 200, scrollable: find.byType(Scrollable).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Keluar'));
    await tester.pumpAndSettle();

    expect(auth.loggedOut, isTrue);
    expect(await tokens.read(), isNull);
    expect(find.byKey(const Key('welcome-register')), findsOneWidget);
  });

  testWidgets('hapus akun wajib mengetik HAPUS', (tester) async {
    final tokens = InMemoryTokenStore(testTokens);
    final auth = FakeAuthRepository();
    await pumpApp(tester, tokens: tokens, auth: auth);

    await tester.tap(find.byKey(const Key('nav-settings')));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Hapus akun'), 100);
    await tester.drag(find.byType(ListView).last, const Offset(0, -200));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Hapus akun'));
    await tester.pumpAndSettle();

    final hapus = find.widgetWithText(TextButton, 'Hapus');
    expect(tester.widget<TextButton>(hapus).onPressed, isNull);
    await tester.enterText(find.byKey(const Key('delete-confirm')), 'HAPUS');
    await tester.pump();
    await tester.tap(hapus);
    await tester.pumpAndSettle();

    expect(auth.deleted, isTrue);
    expect(await tokens.read(), isNull);
    expect(find.byKey(const Key('welcome-register')), findsOneWidget);
  });
}
