import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/auth/token_store.dart';
import 'package:senyap/features/posts/location_service.dart';

import 'posts_flow_test.dart' show usePhoneSize;
import 'support/fakes.dart';

Future<void> _composeWithLocation(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('nav-compose')));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('compose-text')), 'Cerita dari kota ini.');
  await tester.scrollUntilVisible(find.byKey(const Key('compose-location')), 200,
      scrollable: find.descendant(of: find.byKey(const Key('compose-list')), matching: find.byType(Scrollable)).first);
    await tester.ensureVisible(find.byKey(const Key('compose-location')));
    await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('compose-location')));
  await tester.pumpAndSettle();
}

Future<void> _submit(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('compose-submit')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('izin ditolak permanen: dialog → Buka Pengaturan (tidak dikirim) → Kirim tanpa lokasi', (tester) async {
    usePhoneSize(tester);
    final repo = FakePostsRepository();
    final location = FakeLocationService.failing(LocationStatus.deniedForever);
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), posts: repo, location: location);
    await _composeWithLocation(tester);

    await _submit(tester);
    expect(find.byKey(const Key('location-dialog')), findsOneWidget);
    expect(find.text('Buka Pengaturan'), findsOneWidget);
    expect(find.text('Kirim tanpa lokasi'), findsOneWidget);
    expect(find.text('Batal'), findsOneWidget);
    await tester.tap(find.byKey(const Key('location-settings')));
    await tester.pumpAndSettle();
    expect(location.openedAppSettings, 1);
    expect(repo.requests.where((r) => r.startsWith('create')), isEmpty, reason: 'tidak mengirim diam-diam');

    await _submit(tester);
    await tester.tap(find.byKey(const Key('location-cancel')));
    await tester.pumpAndSettle();
    expect(repo.requests.where((r) => r.startsWith('create')), isEmpty);

    await _submit(tester);
    await tester.tap(find.byKey(const Key('location-without')));
    await tester.pumpAndSettle();
    expect(repo.requests, contains('create:curhat:bg-curhat-1:-'));
  });

  testWidgets('layanan lokasi mati: Buka Pengaturan membuka pengaturan lokasi HP', (tester) async {
    usePhoneSize(tester);
    final location = FakeLocationService.failing(LocationStatus.serviceDisabled);
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), location: location);
    await _composeWithLocation(tester);
    await _submit(tester);
    expect(find.textContaining('Layanan lokasi di HP-mu sedang mati'), findsOneWidget);
    await tester.tap(find.byKey(const Key('location-settings')));
    await tester.pumpAndSettle();
    expect(location.openedLocationSettings, 1);
    expect(location.openedAppSettings, 0);
  });

  testWidgets('lokasi gagal sementara: Coba lagi → berhasil → post dengan geohash', (tester) async {
    usePhoneSize(tester);
    final repo = FakePostsRepository();
    final location = FakeLocationService(
        results: const [LocationResult(LocationStatus.failed), LocationResult.ok('qqw7xs')]);
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), posts: repo, location: location);
    await _composeWithLocation(tester);
    await _submit(tester);
    expect(find.byKey(const Key('location-retry')), findsOneWidget);
    await tester.tap(find.byKey(const Key('location-retry')));
    await tester.pumpAndSettle();
    expect(repo.requests, contains('create:curhat:bg-curhat-1:qqw7xs'));
  });

  testWidgets('tab Sekitar: izin ditolak permanen → dialog Buka Pengaturan dan pesan jelas', (tester) async {
    usePhoneSize(tester);
    final location = FakeLocationService.failing(LocationStatus.deniedForever);
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), location: location);
    await tester.tap(find.text('Sekitar'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Aktifkan lokasi'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('location-dialog')), findsOneWidget);
    expect(find.byKey(const Key('location-without')), findsNothing);
    await tester.tap(find.byKey(const Key('location-settings')));
    await tester.pumpAndSettle();
    expect(location.openedAppSettings, 1);
    expect(find.textContaining('Akses lokasi untuk Senyap ditolak'), findsOneWidget);
  });
}
