import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/auth/token_store.dart';

import 'support/fakes.dart';

void main() {
  testWidgets('akun baru: pembuka → usia → nomor → kode → buat profil → izin → Feed', (tester) async {
    final tokens = InMemoryTokenStore();
    final auth = FakeAuthRepository();
    await pumpApp(tester, tokens: tokens, auth: auth, profile: FakeProfileRepository(onboarded: false));

    expect(find.text('Selamat datang di Senyap'), findsOneWidget);
    await tester.tap(find.text('Lanjut'));
    await tester.pumpAndSettle();

    // Tombol baru aktif setelah kedua kotak dicentang
    final next = find.widgetWithText(FilledButton, 'Setuju dan lanjut');
    expect(tester.widget<FilledButton>(next).onPressed, isNull);
    await tester.tap(find.text('Saya berusia 18 tahun ke atas.'));
    await tester.tap(find.textContaining('Saya setuju'));
    await tester.pump();
    await tester.tap(next);
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('phone-field')), '0812 3456 7890');
    await tester.tap(find.text('Kirim kode'));
    await tester.pumpAndSettle();
    expect(auth.requestedPhones, ['0812 3456 7890']);

    await tester.enterText(find.byKey(const Key('otp-field')), '123456');
    await tester.tap(find.text('Masuk'));
    await tester.pumpAndSettle();
    expect(find.text('Kode salah.'), findsOneWidget);

    // Snackbar "Kode salah." menutupi tombol; kirim lewat aksi keyboard.
    await tester.enterText(find.byKey(const Key('otp-field')), '000000');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();
    expect(await tokens.read(), isNotNull);

    // Buat profil: saran pertama, lalu "Acak lagi"
    expect(find.text('Alias kamu'), findsOneWidget);
    final first = tester.widget<Text>(find.byKey(const Key('alias-preview'))).data;
    await tester.tap(find.text('Acak lagi'));
    await tester.pumpAndSettle();
    final second = tester.widget<Text>(find.byKey(const Key('alias-preview'))).data;
    expect(second, isNot(first));

    await tester.tap(find.text('Pakai alias ini'));
    await tester.pumpAndSettle();
    expect(find.text('Izin (opsional)'), findsOneWidget);

    await tester.tap(find.text('Mulai'));
    await tester.pumpAndSettle();
    expect(find.text('Belum ada cerita di sini.'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.settings_outlined));
    await tester.pumpAndSettle();
    expect(find.text(second!), findsOneWidget);
    // Batas 7 hari: alias baru belum bisa diganti
    expect(find.textContaining('Bisa diganti lagi mulai'), findsOneWidget);
  });

  testWidgets('akun lama langsung ke Feed setelah OTP', (tester) async {
    await pumpApp(tester, tokens: InMemoryTokenStore(), auth: FakeAuthRepository(isNew: false));
    await tester.tap(find.text('Lanjut'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Saya berusia 18 tahun ke atas.'));
    await tester.tap(find.textContaining('Saya setuju'));
    await tester.pump();
    await tester.tap(find.text('Setuju dan lanjut'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('phone-field')), '081234567890');
    await tester.tap(find.text('Kirim kode'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('otp-field')), '000000');
    await tester.tap(find.text('Masuk'));
    await tester.pumpAndSettle();

    expect(find.text('Belum ada cerita di sini.'), findsOneWidget);
  });
}
