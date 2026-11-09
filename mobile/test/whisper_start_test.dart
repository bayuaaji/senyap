import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/auth/token_store.dart';
import 'package:senyap/features/messages/chat_models.dart';
import 'package:senyap/features/messages/chat_screen.dart';

import 'posts_flow_test.dart' show usePhoneSize;
import 'support/fakes.dart';

/// Percakapan baru hasil request Bisik (status REQUESTED, pembuka seq 1).
Conversation _requested() => Conversation.fromJson({
      'id': '77',
      'status': 'REQUESTED',
      'role': 'initiator',
      'peer': {
        'public_id': 'p-77',
        'name': 'Rubah Biru',
        'tag': '1203',
        'color': '#1E88E5',
        'gender': 'unspecified',
        'avatar': null,
      },
      'origin': {'type': 'POST', 'id': '1', 'excerpt': 'Aku resign minggu depan'},
      'last_seq': 1,
      'last_message': null,
      'unread_count': 0,
      'last_message_at': '2026-11-09T00:00:00Z',
    });

class _StartRepo extends EmptyChatRepository {
  final started = <String>[];

  @override
  Future<Conversation> start({
    required String originType,
    required String originId,
    required String firstMessage,
    required String clientMsgId,
  }) async {
    started.add('$originType:$originId:$firstMessage');
    return _requested();
  }

  @override
  Future<Conversation> detail(String id) async => _requested();

  @override
  Future<List<ChatMessage>> messages(String id, {int? beforeSeq, int? afterSeq, int limit = 50}) async => const [];
}

void main() {
  testWidgets('Bisik pertama dari post membuka layar chat tanpa error navigator', (tester) async {
    usePhoneSize(tester);
    final chat = _StartRepo();
    await pumpApp(
      tester,
      tokens: InMemoryTokenStore(testTokens),
      posts: FakePostsRepository(posts: [samplePost()]),
      chat: chat,
    );

    await tester.tap(find.byKey(const Key('post-whisper')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('opening')), 'Halo, boleh ngobrol?');
    await tester.tap(find.byKey(const Key('send-whisper')));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(chat.started, ['POST:${samplePost().id}:Halo, boleh ngobrol?']);
    expect(find.byType(ChatScreen), findsOneWidget);
  });

  testWidgets('Bisik pertama dari halaman detail post membuka layar chat tanpa error navigator', (tester) async {
    usePhoneSize(tester);
    final chat = _StartRepo();
    await pumpApp(
      tester,
      tokens: InMemoryTokenStore(testTokens),
      posts: FakePostsRepository(posts: [samplePost()]),
      chat: chat,
    );

    await tester.tap(find.text(samplePost().body));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('post-whisper')).first);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('opening')), 'Halo dari detail');
    await tester.tap(find.byKey(const Key('send-whisper')));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byType(ChatScreen), findsOneWidget);
  });
}
