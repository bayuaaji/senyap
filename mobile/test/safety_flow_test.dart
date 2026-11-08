import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/auth/token_store.dart';
import 'package:senyap/features/safety/safety_repository.dart';

import 'posts_flow_test.dart' show usePhoneSize;
import 'support/fakes.dart';

void main() {
  testWidgets('laporkan post dari feed: pilih alasan → terkirim → tawaran blokir → post hilang', (tester) async {
    usePhoneSize(tester);
    final safety = FakeSafetyRepository();
    await pumpApp(tester,
        tokens: InMemoryTokenStore(testTokens), posts: FakePostsRepository(posts: [samplePost()]), safety: safety);

    await tester.tap(find.byKey(const Key('post-more')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('safety-report')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('reason-harassment')));
    await tester.pumpAndSettle();
    expect(safety.reports, ['POST:101:harassment']);
    expect(find.text('Terima kasih, laporanmu sudah kami terima'), findsOneWidget);

    await tester.tap(find.byKey(const Key('report-then-block')));
    await tester.pumpAndSettle();
    expect(safety.blockCalls, ['POST:101']);
    expect(find.text('Akun diblokir.'), findsOneWidget);
    expect(find.byKey(const Key('post-more')), findsNothing, reason: 'post akun yang diblokir hilang dari feed');
  });

  testWidgets('blokir langsung lalu buka blokir dari Pengaturan', (tester) async {
    usePhoneSize(tester);
    final safety = FakeSafetyRepository();
    await pumpApp(tester,
        tokens: InMemoryTokenStore(testTokens), posts: FakePostsRepository(posts: [samplePost()]), safety: safety);

    await tester.tap(find.byKey(const Key('post-more')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('safety-block')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirm-block')));
    await tester.pumpAndSettle();
    expect(safety.blockCalls, ['POST:101']);

    await tester.tap(find.byIcon(Icons.settings_outlined));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('settings-blocks')));
    await tester.pumpAndSettle();
    expect(find.text('Rubah Biru #1203'), findsOneWidget);
    await tester.tap(find.text('Buka blokir'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirm-unblock')));
    await tester.pumpAndSettle();
    expect(safety.unblocked, ['b1']);
    expect(find.text('Belum ada akun yang kamu blokir.'), findsOneWidget);
  });

  testWidgets('dokumen legal bisa dibuka dari Pengaturan', (tester) async {
    usePhoneSize(tester);
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens));
    await tester.tap(find.byIcon(Icons.settings_outlined));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('legal-guidelines')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('legal-body')), findsOneWidget);
    expect(find.textContaining('Pedoman Komunitas Senyap'), findsWidgets);
  });

  test('alasan laporan mengikuti FS-07', () {
    expect(reportReasons.keys, containsAll(['harassment', 'threat', 'minor_safety', 'self_harm', 'doxxing']));
  });
}
