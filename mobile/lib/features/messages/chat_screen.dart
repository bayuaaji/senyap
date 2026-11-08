import 'dart:async';
import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/api/api_error.dart';
import '../profile/alias_avatar.dart';
import 'chat_engine.dart';
import 'chat_models.dart';
import 'chat_providers.dart';
import 'chat_repository.dart';
import 'chat_time.dart';

/// Layar chat Bisik (functional spec FS-05 §5.2).
class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key, required this.conversationId});

  final String conversationId;

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  StreamSubscription<ChatEvent>? _sub;
  Conversation? _conv;
  List<ChatMessage> _messages = const [];
  (int, int) _peer = (0, 0);
  ApiError? _error;
  DateTime? _typingUntil;
  var _contactWarned = false;
  final _revealed = <String>{};

  String get _id => widget.conversationId;
  ChatEngine? get _engine => ref.read(chatEngineProvider);

  @override
  void initState() {
    super.initState();
    _sub = ref.read(chatEngineProvider)?.events.listen(_onEvent);
    _load();
  }

  @override
  void dispose() {
    _sub?.cancel();
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _onEvent(ChatEvent e) {
    switch (e) {
      case ConversationChanged(:final conversationId) when conversationId == _id:
        _reloadLocal();
      case InboxChanged():
        _reloadConversation();
      case PeerTyping(:final conversationId) when conversationId == _id:
        setState(() => _typingUntil = DateTime.now().add(const Duration(seconds: 5)));
        Future.delayed(const Duration(seconds: 5), () {
          if (mounted) setState(() {});
        });
      case SendFailed(:final conversationId, :final error) when conversationId == _id:
        _snack(error.message);
      default:
        break;
    }
  }

  Future<void> _load() async {
    await _reloadConversation();
    try {
      await _engine?.refresh(_id);
    } on ApiError catch (e) {
      if (mounted) setState(() => _error = e);
    }
    await _reloadLocal();
  }

  Future<void> _reloadConversation() async {
    try {
      final c = await ref.read(chatRepositoryProvider).detail(_id);
      if (!mounted) return;
      setState(() {
        _conv = c;
        _error = null;
      });
    } on ApiError catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _reloadLocal() async {
    final engine = _engine;
    if (engine == null) return;
    final msgs = await engine.messagesOf(_id);
    final peer = await engine.store.peerStatus(_id);
    if (!mounted) return;
    setState(() {
      _messages = msgs;
      _peer = peer;
    });
    final lastTheirs = msgs.lastWhere((m) => !m.mine && m.seq != null, orElse: () => msgs.isEmpty ? _none : msgs.last);
    final seq = lastTheirs.seq;
    if (seq != null && !lastTheirs.mine && (_conv?.myReadUpTo ?? 0) < seq) {
      unawaited(engine.markRead(_id, seq));
    }
  }

  static final _none = ChatMessage(conversationId: '', mine: true, type: 'TEXT', createdAt: DateTime(2000));

  // ------------------------------------------------------------------ aksi

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty) return;
    if (!_contactWarned && _looksLikeContact(text)) {
      _contactWarned = true;
      final go = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Hati-hati membagikan info pribadi'),
          content: const Text('Nomor HP, akun media sosial, atau alamat bisa membuka identitasmu. Tetap kirim?'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Batal')),
            FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Kirim')),
          ],
        ),
      );
      if (go != true) return;
    }
    _input.clear();
    await _engine?.sendText(_id, text);
  }

  static final _contactPattern = RegExp(r'(\+?\d[\d\s-]{8,}\d)|(@[a-z0-9_.]{3,})|(wa\.me|instagram\.com|t\.me)',
      caseSensitive: false);

  static bool _looksLikeContact(String text) => _contactPattern.hasMatch(text);

  Future<void> _pickImage() async {
    final file = await ImagePicker().pickImage(source: ImageSource.gallery, maxWidth: 2048, imageQuality: 85);
    if (file == null) return;
    try {
      final bytes = await file.readAsBytes();
      final mime = file.mimeType ?? (file.name.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg');
      final mediaId = await ref.read(chatRepositoryProvider).uploadImage(bytes, mime);
      await _engine?.sendImage(_id, mediaId);
    } on ApiError catch (e) {
      _snack(e.message);
    }
  }

  Future<void> _act(Future<void> Function() action, {bool pop = false}) async {
    try {
      await action();
      if (pop && mounted) {
        context.pop();
        return;
      }
      await _reloadConversation();
      ref.invalidate(conversationsProvider('inbox'));
      ref.invalidate(conversationsProvider('requests'));
    } on ApiError catch (e) {
      _snack(e.message);
    }
  }

  Future<bool> _confirm(String title, String body, String yes) async =>
      await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(title),
          content: Text(body),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Batal')),
            FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(yes)),
          ],
        ),
      ) ==
      true;

  void _snack(String text) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _onMessageLongPress(ChatMessage m) async {
    final seq = m.seq;
    if (m.failed) {
      final retry = await _confirm('Pesan gagal terkirim', 'Kirim ulang pesan ini?', 'Kirim ulang');
      if (retry) {
        await _engine?.retryFailed(m.clientMsgId!);
      } else {
        _engine?.discardFailed(m.clientMsgId!);
      }
      return;
    }
    if (!m.mine || seq == null || m.retracted || m.isSystem) return;
    if (DateTime.now().difference(m.createdAt) > const Duration(minutes: 11)) return;
    if (await _confirm('Tarik pesan?', 'Pesan akan diganti "Pesan ditarik" untuk kalian berdua.', 'Tarik')) {
      await _act(() => ref.read(chatRepositoryProvider).retract(_id, seq));
    }
  }

  // ------------------------------------------------------------------ tampilan

  @override
  Widget build(BuildContext context) {
    final c = _conv;
    final typing = _typingUntil != null && DateTime.now().isBefore(_typingUntil!);
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: c == null
            ? const Text('Bisik')
            : Row(children: [
                AliasAvatar(alias: c.peer.asAlias, radius: 18),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(c.peer.name, maxLines: 1, overflow: TextOverflow.ellipsis),
                    Text(typing ? 'sedang mengetik…' : (c.excerpt ?? ''),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall),
                  ]),
                ),
              ]),
        actions: [
          if (c != null)
            PopupMenuButton<String>(
              onSelected: (v) async {
                switch (v) {
                  case 'hide':
                    await _act(() => ref.read(chatRepositoryProvider).hide(_id), pop: true);
                  case 'leave':
                    if (await _confirm('Keluar dari percakapan?',
                        'Lawan bicara akan melihat "Lawan bicara telah pergi". Percakapan tidak bisa dibuka lagi.',
                        'Keluar')) {
                      await _act(() => ref.read(chatRepositoryProvider).leave(_id), pop: true);
                    }
                  case 'block':
                    if (await _confirm('Blokir?',
                        'Percakapan ditutup dan dia tidak bisa berbisik lagi kepadamu dari post mana pun.', 'Blokir')) {
                      await _act(() => ref.read(chatRepositoryProvider).block(_id), pop: true);
                    }
                }
              },
              itemBuilder: (context) => const [
                PopupMenuItem(value: 'hide', child: Text('Hapus dari inbox')),
                PopupMenuItem(value: 'leave', child: Text('Keluar')),
                PopupMenuItem(value: 'block', child: Text('Blokir')),
              ],
            ),
        ],
      ),
      body: Column(children: [
        if (_error != null && c == null)
          Expanded(child: Center(child: Text(_error!.message)))
        else
          Expanded(
            child: ListView.builder(
              controller: _scroll,
              reverse: true,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              itemCount: _messages.length,
              itemBuilder: (context, i) {
                final m = _messages[_messages.length - 1 - i];
                return _Bubble(
                  key: ValueKey(m.seq ?? m.clientMsgId),
                  message: m,
                  delivery: m.delivery(peerDeliveredUpTo: _peerDelivered, peerReadUpTo: _peerRead),
                  revealed: _revealed.contains(m.mediaUrl),
                  onReveal: () => setState(() => _revealed.add(m.mediaUrl!)),
                  onLongPress: () => _onMessageLongPress(m),
                );
              },
            ),
          ),
        if (c != null) _footer(c),
      ]),
    );
  }

  int get _peerDelivered => [_peer.$1, _conv?.peerDeliveredUpTo ?? 0].reduce((a, b) => a > b ? a : b);
  int get _peerRead => [_peer.$2, _conv?.peerReadUpTo ?? 0].reduce((a, b) => a > b ? a : b);

  Widget _footer(Conversation c) {
    switch (c.status) {
      case ConversationStatus.requested when !c.isInitiator:
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(children: [
              Expanded(
                child: OutlinedButton(
                  key: const Key('decline'),
                  onPressed: () => _act(() => ref.read(chatRepositoryProvider).decline(_id), pop: true),
                  child: const Text('Tolak'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton(
                  key: const Key('accept'),
                  onPressed: () => _act(() => ref.read(chatRepositoryProvider).accept(_id)),
                  child: const Text('Terima'),
                ),
              ),
            ]),
          ),
        );
      case ConversationStatus.requested:
        return const _Notice('Menunggu bisikanmu diterima.');
      case ConversationStatus.declined:
        return const _Notice('Permintaan tidak diterima.');
      case ConversationStatus.closed:
        return const _Notice('Lawan bicara telah pergi. Percakapan ini sudah ditutup.');
      case ConversationStatus.active:
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(4, 4, 8, 8),
            child: Row(children: [
              IconButton(icon: const Icon(Icons.image_outlined), tooltip: 'Kirim gambar', onPressed: _pickImage),
              Expanded(
                child: TextField(
                  key: const Key('composer'),
                  controller: _input,
                  minLines: 1,
                  maxLines: 5,
                  maxLength: 2000,
                  textCapitalization: TextCapitalization.sentences,
                  decoration: const InputDecoration(hintText: 'Tulis pesan…', counterText: '', border: OutlineInputBorder()),
                  onChanged: (_) => _engine?.typing(_id),
                ),
              ),
              IconButton(key: const Key('send'), icon: const Icon(Icons.send), onPressed: _send),
            ]),
          ),
        );
    }
  }
}

class _Notice extends StatelessWidget {
  const _Notice(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => SafeArea(
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.all(16),
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          child: Text(text, textAlign: TextAlign.center),
        ),
      );
}

class _Bubble extends StatelessWidget {
  const _Bubble({
    super.key,
    required this.message,
    required this.delivery,
    required this.revealed,
    required this.onReveal,
    required this.onLongPress,
  });

  final ChatMessage message;
  final Delivery delivery;
  final bool revealed;
  final VoidCallback onReveal;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final m = message;
    final scheme = Theme.of(context).colorScheme;
    if (m.isSystem) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Center(
          child: Text('Lawan bicara telah pergi', style: Theme.of(context).textTheme.bodySmall),
        ),
      );
    }
    final content = m.retracted
        ? Text('Pesan ditarik', style: TextStyle(fontStyle: FontStyle.italic, color: scheme.outline))
        : m.isImage && m.mediaUrl != null
            ? _image(context)
            : Text(m.body ?? '');
    return Align(
      alignment: m.mine ? Alignment.centerRight : Alignment.centerLeft,
      child: GestureDetector(
        onLongPress: onLongPress,
        child: Container(
          constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.78),
          margin: const EdgeInsets.symmetric(vertical: 3),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: m.mine ? scheme.primaryContainer : scheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.end, mainAxisSize: MainAxisSize.min, children: [
            content,
            const SizedBox(height: 2),
            Row(mainAxisSize: MainAxisSize.min, children: [
              Text(chatRelative(m.createdAt, DateTime.now()), style: Theme.of(context).textTheme.labelSmall),
              if (m.mine) ...[const SizedBox(width: 4), _DeliveryIcon(delivery)],
            ]),
          ]),
        ),
      ),
    );
  }

  /// Gambar DM ditampilkan blur sampai penerima mengetuk "Lihat".
  Widget _image(BuildContext context) {
    final img = ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: Image.network(message.mediaUrl!, width: 220, fit: BoxFit.cover,
          errorBuilder: (_, _, _) => const SizedBox(width: 220, height: 160, child: Icon(Icons.broken_image))),
    );
    if (message.mine || revealed) return img;
    return GestureDetector(
      onTap: onReveal,
      child: Stack(alignment: Alignment.center, children: [
        ImageFiltered(imageFilter: ImageFilter.blur(sigmaX: 24, sigmaY: 24), child: img),
        const Chip(avatar: Icon(Icons.visibility_outlined, size: 18), label: Text('Lihat')),
      ]),
    );
  }
}

class _DeliveryIcon extends StatelessWidget {
  const _DeliveryIcon(this.delivery);

  final Delivery delivery;

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).textTheme.labelSmall?.color;
    return switch (delivery) {
      Delivery.sending => Icon(Icons.schedule, size: 14, color: color, semanticLabel: 'mengirim'),
      Delivery.failed => const Icon(Icons.error_outline, size: 14, color: Colors.red, semanticLabel: 'gagal'),
      Delivery.sent => Icon(Icons.check, size: 14, color: color, semanticLabel: 'tersimpan'),
      Delivery.delivered => Icon(Icons.done_all, size: 14, color: color, semanticLabel: 'sampai'),
      Delivery.read => const Icon(Icons.done_all, size: 14, color: Colors.blue, semanticLabel: 'dibaca'),
    };
  }
}
