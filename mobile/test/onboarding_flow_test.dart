import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/auth/token_store.dart';
import 'package:senyap/features/profile/profile_models.dart';

import 'posts_flow_test.dart' show usePhoneSize;
import 'support/fakes.dart';

Future<void> tapKey(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

Future<void> tapText(WidgetTester tester, String text) async {
  await tester.tap(find.text(text).last);
  await tester.pumpAndSettle();
}

FilledButton primary(WidgetTester tester, String label) =>
    tester.widget<FilledButton>(find.widgetWithText(FilledButton, label));

String aliasPreview(WidgetTester tester) => tester.widget<Text>(find.byKey(const Key('alias-preview'))).data!;

/// Langkah Lengkapi profil: gender → alias → avatar → Feed.
Future<void> completeProfile(WidgetTester tester, FakeProfileRepository profile) async {
  expect(find.text('Langkah 1 dari 3'), findsOneWidget);
  expect(primary(tester, 'Lanjut').onPressed, isNull, reason: 'gender wajib dipilih');
  await tapKey(tester, 'gender-option-female');
  await tapText(tester, 'Lanjut');
  expect(profile.current.gender, Gender.female);

  // Alias memakai katalog perempuan
  expect(find.text('Langkah 2 dari 3'), findsOneWidget);
  expect(profile.catalogRequests, contains(Gender.female));
  final first = aliasPreview(tester);
  expect(['Kucing', 'Merpati', 'Kupu-kupu'].any(first.startsWith), isTrue, reason: first);
  await tapKey(tester, 'alias-shuffle');
  expect(aliasPreview(tester), isNot(first));

  // Pilih sendiri: kata pertama dari daftar
  await tapKey(tester, 'alias-custom');
  await tapKey(tester, 'alias-first');
  await tapText(tester, 'Merpati');
  expect(aliasPreview(tester), startsWith('Merpati '));
  final chosen = aliasPreview(tester);
  await tapText(tester, 'Lanjut');

  // Avatar
  expect(find.text('Langkah 3 dari 3'), findsOneWidget);
  await tapKey(tester, 'avatar-option-f-kupu');
  await tapText(tester, 'Selesai');
  expect(profile.current.avatar?.id, 'f-kupu');
  expect(profile.current.alias.display, chosen);
  expect(find.text('Belum ada cerita di sini.'), findsOneWidget);
}

void main() {
  testWidgets('layar sambutan: Daftar, Masuk, tautan Syarat & Kebijakan Privasi', (tester) async {
    await pumpApp(tester, tokens: InMemoryTokenStore());
    expect(find.text('Senyap'), findsOneWidget);
    expect(find.byKey(const Key('welcome-register')), findsOneWidget);
    expect(find.byKey(const Key('welcome-login')), findsOneWidget);
    expect(find.textContaining('Kebijakan Privasi', findRichText: true), findsOneWidget);
  });

  testWidgets('daftar dengan email → lengkapi profil (gender → alias → avatar) → Feed', (tester) async {
    usePhoneSize(tester);
    final tokens = InMemoryTokenStore();
    final auth = FakeAuthRepository();
    final profile = FakeProfileRepository(onboarded: false);
    await pumpApp(tester, tokens: tokens, auth: auth, profile: profile);

    await tapKey(tester, 'welcome-register');
    await tapKey(tester, 'register-email');

    await tester.enterText(find.byKey(const Key('email-field')), 'aku@contoh.id');
    await tester.enterText(find.descendant(of: find.byKey(const Key('password-field')), matching: find.byType(EditableText)), 'rahasia123');
    await tester.enterText(
        find.descendant(of: find.byKey(const Key('password-confirm-field')), matching: find.byType(EditableText)), 'rahasia12');
    expect(primary(tester, 'Buat akun').onPressed, isNull, reason: 'harus centang 18+ dan setuju');
    await tapKey(tester, 'consent-checkbox');
    await tapText(tester, 'Buat akun');
    expect(find.text('Kata sandi tidak sama.'), findsOneWidget);
    expect(await tokens.read(), isNull);

    await tester.enterText(
        find.descendant(of: find.byKey(const Key('password-confirm-field')), matching: find.byType(EditableText)), 'rahasia123');
    await tapText(tester, 'Buat akun');
    expect(await tokens.read(), isNotNull);
    expect(auth.accounts['aku@contoh.id'], 'rahasia123');

    await completeProfile(tester, profile);

    await tester.tap(find.byKey(const Key('nav-settings')));
    await tester.pumpAndSettle();
    expect(find.text(profile.current.alias.display), findsOneWidget);
    expect(find.text('🦋'), findsOneWidget);
    expect(find.byKey(const Key('gender-female')), findsOneWidget);
  });

  testWidgets('daftar: email yang sudah terdaftar → pesan jelas', (tester) async {
    usePhoneSize(tester);
    await pumpApp(tester,
        tokens: InMemoryTokenStore(), auth: FakeAuthRepository(accounts: {'aku@contoh.id': 'rahasia123'}));
    await tapKey(tester, 'welcome-register');
    await tapKey(tester, 'register-email');
    await tester.enterText(find.byKey(const Key('email-field')), 'aku@contoh.id');
    for (final k in ['password-field', 'password-confirm-field']) {
      await tester.enterText(find.descendant(of: find.byKey(Key(k)), matching: find.byType(EditableText)), 'rahasia123');
    }
    await tapKey(tester, 'consent-checkbox');
    await tapText(tester, 'Buat akun');
    expect(find.text('Email ini sudah terdaftar. Silakan masuk.'), findsOneWidget);
  });

  testWidgets('daftar dengan nomor HP: centang wajib → OTP → lengkapi profil', (tester) async {
    usePhoneSize(tester);
    final auth = FakeAuthRepository();
    final profile = FakeProfileRepository(onboarded: false);
    await pumpApp(tester, tokens: InMemoryTokenStore(), auth: auth, profile: profile);

    await tapKey(tester, 'welcome-register');
    await tapKey(tester, 'register-phone');
    await tester.enterText(find.byKey(const Key('phone-field')), '0812 3456 7890');
    expect(primary(tester, 'Kirim kode').onPressed, isNull);
    await tapKey(tester, 'consent-checkbox');
    await tapText(tester, 'Kirim kode');
    expect(auth.requestedPhones, ['0812 3456 7890']);

    await tester.enterText(find.byKey(const Key('otp-field')), '123456');
    await tapText(tester, 'Lanjut');
    expect(find.text('Kode salah.'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('otp-field')), '000000');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();

    await completeProfile(tester, profile);
  });

  testWidgets('masuk dengan email: salah kata sandi → pesan; benar → Feed', (tester) async {
    usePhoneSize(tester);
    final tokens = InMemoryTokenStore();
    await pumpApp(tester, tokens: tokens, auth: FakeAuthRepository(accounts: {'aku@contoh.id': 'rahasia123'}));

    await tapKey(tester, 'welcome-login');
    await tester.enterText(find.byKey(const Key('email-field')), 'aku@contoh.id');
    await tester.enterText(find.descendant(of: find.byKey(const Key('password-field')), matching: find.byType(EditableText)), 'salah1234');
    await tapText(tester, 'Masuk');
    expect(find.text('Email atau kata sandi salah.'), findsOneWidget);
    expect(await tokens.read(), isNull);

    await tester.enterText(find.descendant(of: find.byKey(const Key('password-field')), matching: find.byType(EditableText)), 'rahasia123');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();
    expect(find.text('Belum ada cerita di sini.'), findsOneWidget);
  });

  testWidgets('masuk: akun lama yang belum onboarding diarahkan ke Lengkapi profil', (tester) async {
    usePhoneSize(tester);
    await pumpApp(tester,
        tokens: InMemoryTokenStore(),
        auth: FakeAuthRepository(accounts: {'aku@contoh.id': 'rahasia123'}),
        profile: FakeProfileRepository(onboarded: false));
    await tapKey(tester, 'welcome-login');
    await tester.enterText(find.byKey(const Key('email-field')), 'aku@contoh.id');
    await tester.enterText(find.descendant(of: find.byKey(const Key('password-field')), matching: find.byType(EditableText)), 'rahasia123');
    await tapText(tester, 'Masuk');
    expect(find.text('Langkah 1 dari 3'), findsOneWidget);
  });

  testWidgets('sesi tersimpan tapi belum onboarding → langsung Lengkapi profil', (tester) async {
    usePhoneSize(tester);
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), profile: FakeProfileRepository(onboarded: false));
    expect(find.text('Langkah 1 dari 3'), findsOneWidget);
  });

  testWidgets('lupa kata sandi: email → kode + kata sandi baru → masuk dengan kata sandi baru', (tester) async {
    usePhoneSize(tester);
    final auth = FakeAuthRepository(accounts: {'aku@contoh.id': 'lama12345'});
    await pumpApp(tester, tokens: InMemoryTokenStore(), auth: auth);

    await tapKey(tester, 'welcome-login');
    await tester.enterText(find.byKey(const Key('email-field')), 'aku@contoh.id');
    await tapKey(tester, 'forgot-password');
    expect(find.text('Lupa kata sandi'), findsOneWidget);
    expect(find.text('aku@contoh.id'), findsOneWidget, reason: 'email dibawa dari layar Masuk');
    await tapText(tester, 'Kirim kode');
    expect(auth.forgotRequests, ['aku@contoh.id']);

    await tester.enterText(find.byKey(const Key('reset-code-field')), '000000');
    await tester.enterText(
        find.descendant(of: find.byKey(const Key('new-password-field')), matching: find.byType(EditableText)), 'baru12345');
    await tapText(tester, 'Simpan kata sandi baru');
    expect(auth.accounts['aku@contoh.id'], 'baru12345');
    expect(find.byKey(const Key('forgot-password')), findsOneWidget, reason: 'kembali ke layar Masuk');

    await tester.enterText(find.descendant(of: find.byKey(const Key('password-field')), matching: find.byType(EditableText)), 'baru12345');
    await tapText(tester, 'Masuk');
    expect(find.text('Belum ada cerita di sini.'), findsOneWidget);
  });

  testWidgets('masuk dengan nomor HP (OTP) → Feed', (tester) async {
    usePhoneSize(tester);
    await pumpApp(tester, tokens: InMemoryTokenStore(), auth: FakeAuthRepository(isNew: false));
    await tapKey(tester, 'welcome-login');
    await tapKey(tester, 'login-phone');
    await tester.enterText(find.byKey(const Key('phone-field')), '081234567890');
    await tapText(tester, 'Kirim kode');
    await tester.enterText(find.byKey(const Key('otp-field')), '000000');
    await tapText(tester, 'Lanjut');
    expect(find.text('Belum ada cerita di sini.'), findsOneWidget);
  });
}
