import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/features/messages/chat_models.dart';
import 'package:senyap/features/messages/chat_providers.dart';
import 'package:senyap/features/messages/chat_repository.dart';
import 'package:senyap/features/messages/chat_time.dart';
import 'package:senyap/features/messages/messages_screen.dart';

Conversation conv(String id, String status, {required String role, String? body, int unread = 0}) =>
    Conversation.fromJson({
      'id': id,
      'status': status,
      'role': role,
      'peer': {'public_id': 'p-$id', 'name': 'Rubah Biru', 'tag': '1234', 'color': '#1E88E5'},
      'origin': {'type': 'POST', 'id': '9', 'excerpt': 'Aku resign minggu depan'},
      'last_seq': 1,
      'last_message': body == null
          ? null
          : {'conversation_id': id, 'seq': 1, 'sender': 'them', 'type': 'TEXT', 'body': body,
             'retracted': false, 'created_at': '2026-11-09T00:00:00Z'},
      'unread_count': unread,
      'last_message_at': '2026-11-09T00:00:00Z',
    });

class ListRepo implements ChatRepository {
  @override
  Future<ConversationPage> list({required String box, String? cursor}) async => ConversationPage(
        box == 'inbox'
            ? [
                conv('1', 'ACTIVE', role: 'initiator', body: 'Halo juga', unread: 2),
                conv('2', 'REQUESTED', role: 'initiator'),
                conv('3', 'DECLINED', role: 'initiator'),
              ]
            : [conv('4', 'REQUESTED', role: 'recipient', body: 'Boleh ngobrol?')],
        null,
      );

  @override
  Future<ChatBadge> badge() async => const ChatBadge(unreadMessages: 2, requests: 1);

  @override
  Future<String> uploadImage(Uint8List bytes, String mimeType) => throw UnimplementedError();

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError();
}

void main() {
  testWidgets('inbox menampilkan pesan, menunggu diterima, ditolak; tab Permintaan terpisah', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        chatRepositoryProvider.overrideWithValue(ListRepo()),
        chatEngineProvider.overrideWithValue(null),
      ],
      child: const MaterialApp(home: MessagesScreen()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Halo juga'), findsOneWidget);
    expect(find.text('2'), findsOneWidget); // belum dibaca
    expect(find.text('Menunggu diterima'), findsOneWidget);
    expect(find.text('Permintaan tidak diterima'), findsOneWidget);
    expect(find.text('Boleh ngobrol?'), findsNothing);
    expect(find.byKey(const Key('requests-badge')), findsOneWidget);

    await tester.tap(find.text('Permintaan'));
    await tester.pumpAndSettle();
    expect(find.text('Boleh ngobrol?'), findsOneWidget);
  });

  test('waktu relatif chat (functional spec §4.1)', () {
    final now = DateTime.utc(2026, 11, 9, 12);
    expect(chatRelative(now.subtract(const Duration(seconds: 30)), now), 'baru saja');
    expect(chatRelative(now.add(const Duration(minutes: 2)), now), 'baru saja');
    expect(chatRelative(now.subtract(const Duration(minutes: 5)), now), '5 menit yang lalu');
    expect(chatRelative(now.subtract(const Duration(hours: 1, minutes: 59)), now), '1 jam yang lalu');
    expect(chatRelative(now.subtract(const Duration(days: 12)), now), '12 hari yang lalu');
    expect(chatRelative(now.subtract(const Duration(days: 125)), now), '4 bulan yang lalu');
    expect(chatRelative(now.subtract(const Duration(days: 800)), now), '2 tahun yang lalu');
  });
}
