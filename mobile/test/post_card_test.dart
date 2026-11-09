import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/features/posts/post_card.dart';
import 'package:senyap/features/posts/post_models.dart';

import 'support/fakes.dart';

Widget _card(Post post, {VoidCallback? onMore}) => MaterialApp(
      home: Scaffold(body: ListView(children: [PostCard(post: post, onMore: onMore)])),
    );

void main() {
  testWidgets('kartu post: avatar emoji + ikon gender penulis, inisial bila tanpa avatar', (tester) async {
    await tester.pumpWidget(_card(samplePost()));
    expect(find.text('🦊'), findsOneWidget);
    expect(find.byKey(const Key('gender-female')), findsOneWidget);

    await tester.pumpWidget(_card(samplePost(mine: true)));
    expect(find.byKey(const Key('avatar-emoji')), findsNothing);
    expect(find.text('K'), findsOneWidget, reason: 'inisial Kelinci Jingga');
    expect(find.byKey(const Key('gender-male')), findsNothing);
    expect(find.byKey(const Key('gender-female')), findsNothing);
  });

  testWidgets('tag kategori di tengah di atas teks; tombol ⋯ di baris penulis, di atas foto', (tester) async {
    await tester.pumpWidget(_card(samplePost()));
    final card = tester.getRect(find.descendant(of: find.byType(Card), matching: find.byType(AspectRatio)));
    final tag = tester.getRect(find.byKey(const Key('post-category')));
    final text = tester.getRect(find.text(samplePost().body));
    expect((tag.center.dx - card.center.dx).abs(), lessThan(1), reason: 'tag di tengah kartu');
    expect(tag.bottom, lessThanOrEqualTo(text.top), reason: 'tag di atas teks');

    await tester.pumpWidget(_card(samplePost(), onMore: () {}));
    final icon = tester.getRect(find.descendant(of: find.byKey(const Key('post-more')), matching: find.byType(Icon)));
    final photo = tester.getRect(find.descendant(of: find.byType(Card), matching: find.byType(AspectRatio)));
    expect(photo.right - icon.right, greaterThanOrEqualTo(8), reason: 'ikon tidak menempel ke tepi kanan');
    expect(icon.bottom, lessThanOrEqualTo(photo.top), reason: 'menu di baris penulis, bukan di atas foto');
  });
}
