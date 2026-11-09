import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/auth/token_store.dart';
import 'package:senyap/features/posts/post_models.dart';

import 'support/fakes.dart';

/// Layar seukuran HP (360×800 dp) supaya kartu 4:5 muat utuh.
void usePhoneSize(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
}

void main() {
  testWidgets('feed Terbaru menampilkan kartu post: alias, kota, waktu relatif, tombol Bisik membuka layar Bisik', (tester) async {
    usePhoneSize(tester);
    final repo = FakePostsRepository(posts: [samplePost()]);
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), posts: repo);

    expect(find.text('Aku belum bilang ke siapa-siapa kalau aku resign minggu depan.'), findsOneWidget);
    expect(find.text('Rubah Biru #1203'), findsOneWidget);
    expect(find.text('Yogyakarta'), findsOneWidget);
    expect(find.text('5 menit yang lalu'), findsOneWidget);
    expect(repo.requests, contains('latest'));

    await tester.tap(find.byKey(const Key('post-whisper')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('send-whisper')), findsOneWidget);
  });

  testWidgets('hati optimistis lalu dikirim ke server', (tester) async {
    usePhoneSize(tester);
    final repo = FakePostsRepository(posts: [samplePost()]);
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), posts: repo);

    await tester.tap(find.byKey(const Key('post-heart')));
    await tester.pumpAndSettle();
    expect(find.text('4'), findsOneWidget);
    expect(repo.hearts['101'], isTrue);
  });

  testWidgets('tab Sekitar meminta lokasi saat diketuk dan memakai geohash', (tester) async {
    usePhoneSize(tester);
    final repo = FakePostsRepository(posts: [samplePost()]);
    final location = FakeLocationService();
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), posts: repo, location: location);

    await tester.tap(find.text('Sekitar'));
    await tester.pumpAndSettle();
    expect(location.asked, 0, reason: 'izin lokasi tidak diminta otomatis');
    await tester.tap(find.text('Aktifkan lokasi'));
    await tester.pumpAndSettle();
    expect(location.asked, 1);
    expect(repo.requests, contains('nearby:qqw7xs'));
  });

  testWidgets('layar balasan: lencana Penulis, Bisik dari balasan, kirim balasan', (tester) async {
    usePhoneSize(tester);
    final post = samplePost();
    final repo = FakePostsRepository(posts: [post], replies: {
      post.id: [
        Reply(
          id: '201',
          postId: post.id,
          author: const Author(publicId: 'bbbb', name: 'Rubah Biru', tag: '1203', color: '#1E88E5', isPostAuthor: true),
          body: 'Makasih semuanya.',
          createdAt: DateTime.now().toUtc().subtract(const Duration(hours: 3)),
          canWhisper: true,
        ),
      ],
    });
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), posts: repo);

    await tester.tap(find.text('Aku belum bilang ke siapa-siapa kalau aku resign minggu depan.'));
    await tester.pumpAndSettle();
    expect(find.text('Makasih semuanya.'), findsOneWidget);
    expect(find.byKey(const Key('author-badge')), findsOneWidget);
    expect(find.text('3 jam yang lalu'), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('reply-whisper')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('reply-whisper')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('send-whisper')), findsOneWidget);
    expect(find.text('"Makasih semuanya."'), findsOneWidget);
    await tester.tap(find.byKey(const Key('back')).last);
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('reply-input')), 'Semangat ya!');
    await tester.tap(find.byKey(const Key('reply-send')));
    await tester.pumpAndSettle();
    expect(find.text('Semangat ya!'), findsOneWidget);
    expect(find.text('2 balasan'), findsOneWidget);
  });

  testWidgets('editor post: tulis, pilih kategori + latar, lokasi, kirim → layar post', (tester) async {
    usePhoneSize(tester);
    final repo = FakePostsRepository();
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), posts: repo);

    await tester.tap(find.byKey(const Key('nav-compose')));
    await tester.pumpAndSettle();
    final submit = find.byKey(const Key('compose-submit'));
    expect(tester.widget<FilledButton>(submit).onPressed, isNull, reason: 'teks kosong');

    await tester.enterText(find.byKey(const Key('compose-text')), 'Hari ini aku berani bilang tidak.');
    await tester.tap(find.text('Kerja'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.byKey(const Key('compose-location')), 200,
        scrollable: find.descendant(of: find.byKey(const Key('compose-list')), matching: find.byType(Scrollable)).first);
    await tester.ensureVisible(find.byKey(const Key('compose-location')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('compose-location')));
    await tester.pumpAndSettle();
    await tester.tap(submit);
    await tester.pumpAndSettle();

    expect(repo.requests, contains('create:kerja:bg-kerja-1:qqw7xs'));
    expect(find.text('Cerita'), findsOneWidget);
    expect(find.text('Hari ini aku berani bilang tidak.'), findsOneWidget);
    expect(find.text('Belum ada balasan.'), findsOneWidget);
  });

  testWidgets('editor post: upload latar sendiri dipakai saat kirim', (tester) async {
    usePhoneSize(tester);
    final repo = FakePostsRepository();
    await pumpApp(tester, tokens: InMemoryTokenStore(testTokens), posts: repo);

    await tester.tap(find.byKey(const Key('nav-compose')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('compose-text')), 'Latar sendiri.');
    await tester.ensureVisible(find.byKey(const Key('compose-upload')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('compose-upload')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('compose-submit')));
    await tester.pumpAndSettle();

    expect(repo.requests, containsAll(['upload:image/jpeg', 'create:curhat:uploaded-1:-']));
  });
}
