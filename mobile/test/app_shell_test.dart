import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/app/app.dart';

void main() {
  testWidgets('menampilkan 5 tab dan mulai di Feed', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: SenyapApp()));
    await tester.pumpAndSettle();

    for (final label in ['Feed', 'Tulis', 'Pesan', 'Notifikasi', 'Pengaturan']) {
      expect(
        find.descendant(of: find.byType(NavigationBar), matching: find.text(label)),
        findsOneWidget,
      );
    }
    expect(find.text('Belum ada cerita di sini.'), findsOneWidget);
  });

  testWidgets('pindah tab ke Pesan lalu Pengaturan', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: SenyapApp()));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.chat_bubble_outline).last);
    await tester.pumpAndSettle();
    expect(find.text('Bisikanmu akan muncul di sini.'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.settings_outlined));
    await tester.pumpAndSettle();
    expect(find.text('Pengaturan akun dan alias.'), findsOneWidget);
  });
}
