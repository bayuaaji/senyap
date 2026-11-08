import 'dart:async';

import 'package:drift_flutter/drift_flutter.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/auth/session.dart';
import '../../core/config.dart';
import '../../core/realtime/realtime_client.dart';
import 'chat_engine.dart';
import 'chat_models.dart';
import 'chat_repository.dart';
import 'chat_store.dart';

final chatStoreProvider = Provider<ChatStore>((ref) {
  final store = ChatStore(driftDatabase(name: 'senyap_chat'));
  ref.onDispose(store.close);
  // Keluar / hapus akun: pesan + antrean lokal ikut dihapus.
  ref.listen(sessionProvider.select((s) => s.status), (prev, next) {
    if (prev == SessionStatus.signedIn && next == SessionStatus.signedOut) unawaited(store.clearAll());
  });
  return store;
});

final realtimeTransportProvider = Provider<RealtimeTransport>((ref) {
  final tokens = ref.watch(tokenStoreProvider);
  final dio = ref.watch(dioProvider);
  return IoRealtimeClient(
    url: Uri.parse(AppConfig.realtimeUrl),
    accessToken: () async => (await tokens.read())?.accessToken,
    // Request apa pun dengan token kedaluwarsa memicu refresh di AuthInterceptor.
    refreshToken: () async {
      try {
        await dio.get<dynamic>('/v1/me/profile');
        return true;
      } catch (_) {
        return false;
      }
    },
  );
});

/// Mesin Bisik; hanya hidup selama user masuk. `null` saat belum masuk.
final chatEngineProvider = Provider<ChatEngine?>((ref) {
  final signedIn = ref.watch(sessionProvider.select((s) => s.status == SessionStatus.signedIn));
  if (!signedIn) return null;
  final engine = ChatEngine(
    store: ref.watch(chatStoreProvider),
    repository: ref.watch(chatRepositoryProvider),
    transport: ref.watch(realtimeTransportProvider),
  )..start();
  ref.onDispose(() => unawaited(engine.stop()));
  return engine;
});

/// Daftar percakapan per tab (`inbox` / `requests`), dimuat ulang saat ada perubahan dari realtime.
final conversationsProvider =
    AsyncNotifierProvider.family<ConversationsNotifier, List<Conversation>, String>(ConversationsNotifier.new);

class ConversationsNotifier extends AsyncNotifier<List<Conversation>> {
  ConversationsNotifier(this.box);

  final String box;
  String? _next;

  bool get hasMore => _next != null;

  @override
  Future<List<Conversation>> build() async {
    final engine = ref.watch(chatEngineProvider);
    if (engine != null) {
      final sub = engine.events.where((e) => e is InboxChanged).listen((_) => ref.invalidateSelf());
      ref.onDispose(sub.cancel);
    }
    final page = await ref.read(chatRepositoryProvider).list(box: box);
    _next = page.nextCursor;
    return page.items;
  }

  Future<void> loadMore() async {
    final cursor = _next;
    final current = state.value;
    if (cursor == null || current == null) return;
    final page = await ref.read(chatRepositoryProvider).list(box: box, cursor: cursor);
    _next = page.nextCursor;
    state = AsyncData([...current, ...page.items]);
  }
}

/// Badge tab Pesan: belum dibaca + permintaan masuk.
final chatBadgeProvider = FutureProvider<ChatBadge>((ref) async {
  final engine = ref.watch(chatEngineProvider);
  if (engine != null) {
    final sub = engine.events.where((e) => e is InboxChanged).listen((_) => ref.invalidateSelf());
    ref.onDispose(sub.cancel);
  }
  return ref.read(chatRepositoryProvider).badge();
});
