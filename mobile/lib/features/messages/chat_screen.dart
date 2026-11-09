import 'dart:async';
import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../profile/alias_avatar.dart';
import '../safety/safety_actions.dart';
import '../safety/safety_repository.dart';
import 'chat_engine.dart';
import 'chat_models.dart';
import 'chat_providers.dart';
import 'chat_repository.dart';
import 'image_viewer.dart';

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
      case SupportCardSuggested(:final conversationId) when conversationId == _id:
        showSupportCard(context, ref);
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
      final go = await showConfirm(
        context,
        icon: Icons.shield_outlined,
        title: 'Hati-hati membagikan info pribadi',
        message: 'Nomor HP, akun media sosial, atau alamat bisa membuka identitasmu. Tetap kirim?',
        confirmLabel: 'Tetap kirim',
      );
      if (!go) return;
    }
    _input.clear();
    HapticFeedback.lightImpact();
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
      if (!mounted) return;
      // Pratinjau dulu; baru diunggah setelah user menekan Kirim.
      final send = await ImageViewer.open(context, image: MemoryImage(bytes), sendLabel: 'Kirim');
      if (send != true) return;
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

  Future<bool> _confirm(String title, String body, String yes, {bool destructive = false, IconData? icon}) =>
      showConfirm(context, title: title, message: body, confirmLabel: yes, destructive: destructive, icon: icon);

  void _snack(String text) {
    if (mounted) showToast(context, text, tone: ToastTone.error);
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
    if (!m.mine && seq != null && !m.isSystem) {
      // Pesan lawan bicara: laporkan (snapshot 20 pesan di sekitarnya disimpan server, termasuk bila nanti ditarik).
      await showReportSheet(context, ref, SafetyTarget.message, _id, seq: seq,
          onBlocked: () => Navigator.of(context).maybePop());
      return;
    }
    if (!m.mine || seq == null || m.retracted || m.isSystem) return;
    if (DateTime.now().difference(m.createdAt) > const Duration(minutes: 11)) return;
    if (await _confirm('Tarik pesan?', 'Pesan akan diganti "Pesan ditarik" untuk kalian berdua.', 'Tarik',
        icon: Icons.undo_rounded)) {
      await _act(() => ref.read(chatRepositoryProvider).retract(_id, seq));
    }
  }

  // ------------------------------------------------------------------ tampilan

  Future<void> _menu(String v) async {
    switch (v) {
      case 'hide':
        await _act(() => ref.read(chatRepositoryProvider).hide(_id), pop: true);
      case 'leave':
        if (await _confirm('Keluar dari percakapan?',
            'Lawan bicara akan melihat "Lawan bicara telah pergi". Percakapan tidak bisa dibuka lagi.', 'Keluar',
            destructive: true, icon: Icons.logout_rounded)) {
          await _act(() => ref.read(chatRepositoryProvider).leave(_id), pop: true);
        }
      case 'report':
        await showReportSheet(context, ref, SafetyTarget.conversation, _id,
            onBlocked: () => Navigator.of(context).maybePop());
      case 'block':
        if (await _confirm('Blokir?',
            'Percakapan ditutup dan dia tidak bisa berbisik lagi kepadamu dari post mana pun.', 'Blokir',
            destructive: true, icon: Icons.block_rounded)) {
          await _act(() => ref.read(chatRepositoryProvider).block(_id), pop: true);
        }
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = _conv;
    final colors = context.colors;
    final typing = _typingUntil != null && DateTime.now().isBefore(_typingUntil!);
    PopupMenuItem<String> item(String value, IconData icon, String label, {bool danger = false}) => PopupMenuItem(
          value: value,
          child: Row(children: [
            Icon(icon, size: 19, color: danger ? colors.danger : colors.textSecondary),
            const SizedBox(width: Space.sm),
            Text(label, style: TextStyle(color: danger ? colors.danger : null)),
          ]),
        );
    final n = _messages.length;
    return Scaffold(
      appBar: SenyapBar(
        titleWidget: c == null
            ? Text('Bisik', style: context.text.titleMedium)
            : Row(children: [
                Hero(
                  tag: 'chat-avatar:${c.id}',
                  child: AliasAvatar(alias: c.peer.asAlias, emoji: c.peer.avatar, radius: 17),
                ),
                const SizedBox(width: Space.sm),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                    AliasName(
                      key: const Key('chat-peer-name'),
                      text: c.peer.name,
                      gender: c.peer.gender,
                      style: context.text.titleSmall,
                    ),
                    AnimatedSwitcher(
                      duration: Motion.fast,
                      child: Text(
                        typing ? 'sedang mengetik…' : _statusLine(c),
                        key: ValueKey(typing),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: context.text.bodySmall?.copyWith(
                          fontWeight: FontWeight.w400,
                          color: typing ? colors.accent : colors.textTertiary,
                        ),
                      ),
                    ),
                  ]),
                ),
              ]),
        actions: [
          if (c != null)
            PopupMenuButton<String>(
              tooltip: 'Lainnya',
              icon: Icon(Icons.more_horiz_rounded, color: colors.text),
              onSelected: _menu,
              itemBuilder: (context) => [
                item('hide', Icons.inbox_outlined, 'Hapus dari inbox'),
                item('leave', Icons.logout_rounded, 'Keluar'),
                item('report', Icons.flag_outlined, 'Laporkan'),
                item('block', Icons.block_rounded, 'Blokir', danger: true),
              ],
            ),
        ],
      ),
      body: Column(children: [
        if (_error == null && c == null && n == 0)
          const Expanded(child: Loader())
        else if (_error != null && c == null)
          Expanded(
            child: Center(
              child: EmptyState(
                icon: Icons.cloud_off_rounded,
                title: 'Percakapan tidak bisa dibuka',
                message: _error!.message,
              ),
            ),
          )
        else
          Expanded(
            child: ListView.builder(
              controller: _scroll,
              reverse: true,
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: const EdgeInsets.fromLTRB(Space.md, Space.md, Space.md, Space.lg),
              itemCount: n + 1,
              itemBuilder: (context, i) {
                if (i == n) return c == null ? const SizedBox.shrink() : _OriginCard(conversation: c);
                final m = _messages[n - 1 - i];
                // Pesan berurutan dari pengirim yang sama dirapatkan.
                final newer = i == 0 ? null : _messages[n - i];
                final older = i == n - 1 ? null : _messages[n - 2 - i];
                final newDay = older == null || !_sameDay(older.createdAt, m.createdAt);
                final bubble = _Bubble(
                  key: ValueKey(m.seq ?? m.clientMsgId),
                  message: m,
                  groupedWithNext: newer != null && newer.mine == m.mine && !newer.isSystem &&
                      _sameDay(newer.createdAt, m.createdAt),
                  groupedWithPrev: !newDay && older.mine == m.mine && !older.isSystem,
                  delivery: m.delivery(peerDeliveredUpTo: _peerDelivered, peerReadUpTo: _peerRead),
                  revealed: _revealed.contains(m.mediaUrl),
                  onReveal: () => setState(() => _revealed.add(m.mediaUrl!)),
                  onLongPress: () => _onMessageLongPress(m),
                );
                if (!newDay) return bubble;
                return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  _DaySeparator(m.createdAt.toLocal()),
                  bubble,
                ]);
              },
            ),
          ),
        if (c != null) _footer(c),
      ]),
    );
  }

  static bool _sameDay(DateTime a, DateTime b) {
    final x = a.toLocal(), y = b.toLocal();
    return x.year == y.year && x.month == y.month && x.day == y.day;
  }

  static String _statusLine(Conversation c) => switch (c.status) {
        ConversationStatus.requested when c.isInitiator => 'Menunggu diterima',
        ConversationStatus.requested => 'Ingin berbisik denganmu',
        ConversationStatus.declined => 'Permintaan tidak diterima',
        ConversationStatus.closed => 'Percakapan ditutup',
        ConversationStatus.active => 'Bisik · hanya kalian berdua',
      };

  int get _peerDelivered => [_peer.$1, _conv?.peerDeliveredUpTo ?? 0].reduce((a, b) => a > b ? a : b);
  int get _peerRead => [_peer.$2, _conv?.peerReadUpTo ?? 0].reduce((a, b) => a > b ? a : b);

  Widget _footer(Conversation c) {
    final colors = context.colors;
    switch (c.status) {
      case ConversationStatus.requested when !c.isInitiator:
        return SafeArea(
          top: false,
          child: Container(
            padding: const EdgeInsets.fromLTRB(Space.gutter, Space.lg, Space.gutter, Space.md),
            decoration: BoxDecoration(
              color: colors.surface,
              border: Border(top: BorderSide(color: colors.line, width: 0.6)),
            ),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text('${c.peer.name} ingin berbisik denganmu', style: context.text.headlineSmall),
              const SizedBox(height: Space.xxs),
              const SizedBox(height: Space.xxs),
              Text('Terima untuk mulai mengobrol. Kalau ditolak, dia tidak bisa mengirim pesan lagi di sini.',
                  style: context.text.bodyMedium),
              const SizedBox(height: Space.lg),
              Row(children: [
                Expanded(
                  child: SecondaryButton(
                    key: const Key('decline'),
                    label: 'Tolak',
                    onPressed: () => _act(() => ref.read(chatRepositoryProvider).decline(_id), pop: true),
                  ),
                ),
                const SizedBox(width: Space.sm),
                Expanded(
                  child: PrimaryButton(
                    key: const Key('accept'),
                    tone: ButtonTone.accent,
                    label: 'Terima',
                    onPressed: () => _act(() => ref.read(chatRepositoryProvider).accept(_id)),
                  ),
                ),
              ]),
            ]),
          ),
        );
      case ConversationStatus.requested:
        return const _Notice('Menunggu bisikanmu diterima.', icon: Icons.hourglass_empty_rounded);
      case ConversationStatus.declined:
        return const _Notice('Permintaan tidak diterima.', icon: Icons.do_not_disturb_on_outlined);
      case ConversationStatus.closed:
        return const _Notice('Lawan bicara telah pergi. Percakapan ini sudah ditutup.', icon: Icons.logout_rounded);
      case ConversationStatus.active:
        return _Composer(
          controller: _input,
          onSend: _send,
          onPickImage: _pickImage,
          onChanged: (_) => _engine?.typing(_id),
        );
    }
  }
}

/// Kolom tulis menempel di bawah: tombol foto, input `raised`, tombol kirim jambu yang aktif saat ada teks.
class _Composer extends StatelessWidget {
  const _Composer({required this.controller, required this.onSend, required this.onPickImage, required this.onChanged});

  final TextEditingController controller;
  final VoidCallback onSend;
  final VoidCallback onPickImage;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: c.bg,
        border: Border(top: BorderSide(color: c.line, width: 0.6)),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(Space.xs, Space.sm, Space.md, Space.sm),
          child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
            RoundIconButton(
              icon: Icons.add_photo_alternate_outlined,
              tooltip: 'Kirim foto',
              size: 46,
              iconColor: c.textSecondary,
              onPressed: onPickImage,
            ),
            const SizedBox(width: Space.xxs),
            Expanded(
              child: TextField(
                key: const Key('composer'),
                controller: controller,
                minLines: 1,
                maxLines: 5,
                maxLength: 2000,
                textCapitalization: TextCapitalization.sentences,
                style: ChatText.body(context, mine: false),
                decoration: const InputDecoration(
                  hintText: 'Tulis bisikan…',
                  counterText: '',
                  isDense: true,
                  contentPadding: EdgeInsets.symmetric(horizontal: Space.md, vertical: 13),
                ),
                onChanged: onChanged,
              ),
            ),
            const SizedBox(width: Space.xs),
            ValueListenableBuilder(
              valueListenable: controller,
              builder: (context, value, _) {
                final ready = value.text.trim().isNotEmpty;
                return RoundIconButton(
                  key: const Key('send'),
                  tooltip: 'Kirim',
                  size: 46,
                  icon: Icons.arrow_upward_rounded,
                  color: ready ? c.accent : c.raised,
                  iconColor: ready ? c.onAccent : c.textTertiary,
                  onPressed: onSend,
                );
              },
            ),
          ]),
        ),
      ),
    );
  }
}

/// Kartu asal percakapan di paling atas: potongan cerita tempat Bisik ini bermula.
class _OriginCard extends StatelessWidget {
  const _OriginCard({required this.conversation});

  final Conversation conversation;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final excerpt = conversation.excerpt;
    return Padding(
      padding: const EdgeInsets.fromLTRB(Space.xs, Space.lg, Space.xs, Space.xl),
      child: Column(children: [
        AliasAvatar(alias: conversation.peer.asAlias, emoji: conversation.peer.avatar, radius: 30),
        const SizedBox(height: Space.sm),
        Text('Bisikan dengan ${conversation.peer.name}', style: context.text.headlineSmall, textAlign: TextAlign.center),
        const SizedBox(height: Space.xxs),
        Text('Hanya kalian berdua yang bisa membaca ini.',
            style: context.text.bodyMedium, textAlign: TextAlign.center),
        if (excerpt != null && excerpt.isNotEmpty) ...[
          const SizedBox(height: Space.md),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.fromLTRB(Space.md, Space.sm, Space.md, Space.md),
            decoration: BoxDecoration(
              borderRadius: Radii.all(Radii.md),
              border: Border.all(color: c.line, width: 1),
            ),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('Berawal dari cerita', style: context.text.labelMedium?.copyWith(color: c.textTertiary)),
              const SizedBox(height: Space.xxs),
              Text(excerpt,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: PostType.quote.copyWith(fontSize: 16, color: c.textSecondary)),
            ]),
          ),
        ],
      ]),
    );
  }
}

class _DaySeparator extends StatelessWidget {
  const _DaySeparator(this.day);

  final DateTime day;

  static const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

  String _label() {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final d = DateTime(day.year, day.month, day.day);
    final diff = today.difference(d).inDays;
    if (diff == 0) return 'Hari ini';
    if (diff == 1) return 'Kemarin';
    return '${day.day} ${_months[day.month - 1]}${day.year == now.year ? '' : ' ${day.year}'}';
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Padding(
      padding: const EdgeInsets.only(top: Space.lg, bottom: Space.xs),
      child: Center(
        child: Text(_label(), style: context.text.labelSmall?.copyWith(color: c.textTertiary)),
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice(this.text, {required this.icon});

  final String text;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.fromLTRB(Space.gutter, Space.md, Space.gutter, Space.md),
        decoration: BoxDecoration(
          color: c.bg,
          border: Border(top: BorderSide(color: c.line, width: 0.6)),
        ),
        child: Row(children: [
          Icon(icon, size: 18, color: c.textTertiary),
          const SizedBox(width: Space.sm),
          Expanded(child: Text(text, style: context.text.bodyMedium)),
        ]),
      ),
    );
  }
}

/// Tipografi chat: isi 16 dengan line-height nyaman, jam/status kecil dan redup.
abstract final class ChatText {
  static TextStyle body(BuildContext context, {required bool mine}) {
    final c = context.colors;
    return (Theme.of(context).textTheme.bodyLarge ?? const TextStyle()).copyWith(
      fontSize: 16,
      height: 1.4,
      fontWeight: FontWeight.w400,
      letterSpacing: -0.1,
      color: mine ? c.onAccent : c.text,
    );
  }

  static TextStyle meta(BuildContext context, {required bool mine}) {
    final c = context.colors;
    return (Theme.of(context).textTheme.labelSmall ?? const TextStyle()).copyWith(
      fontSize: 11,
      height: 1.2,
      fontWeight: FontWeight.w500,
      letterSpacing: 0,
      color: mine ? c.onAccentMuted : c.textTertiary,
    );
  }
}

String _clock(DateTime t) {
  final l = t.toLocal();
  return '${l.hour.toString().padLeft(2, '0')}.${l.minute.toString().padLeft(2, '0')}';
}

class _Bubble extends StatelessWidget {
  const _Bubble({
    super.key,
    required this.message,
    required this.delivery,
    required this.revealed,
    required this.onReveal,
    required this.onLongPress,
    this.groupedWithNext = false,
    this.groupedWithPrev = false,
  });

  final ChatMessage message;
  final Delivery delivery;
  final bool revealed;
  final VoidCallback onReveal;
  final VoidCallback onLongPress;

  /// Pesan berikutnya dari pengirim yang sama: jarak lebih rapat, sudut ekor dihilangkan.
  final bool groupedWithNext;
  final bool groupedWithPrev;

  @override
  Widget build(BuildContext context) {
    final m = message;
    final c = context.colors;
    if (m.isSystem) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: Space.sm),
        child: Center(
          child: Text('Lawan bicara telah pergi', style: context.text.bodySmall?.copyWith(color: c.textTertiary)),
        ),
      );
    }
    final isImage = !m.retracted && m.isImage && m.mediaUrl != null;
    // Gambar tampil tanpa gelembung; jam/status di bawahnya memakai warna redup biasa.
    final onBubble = m.mine && !isImage;
    final meta = Row(mainAxisSize: MainAxisSize.min, children: [
      Text(_clock(m.createdAt), style: ChatText.meta(context, mine: onBubble)),
      if (m.mine) ...[const SizedBox(width: 3), _DeliveryIcon(delivery, mine: onBubble)],
    ]);
    final content = m.retracted
        ? Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(Icons.undo_rounded, size: 14, color: ChatText.meta(context, mine: m.mine).color),
            const SizedBox(width: 4),
            Text('Pesan ditarik',
                style: ChatText.body(context, mine: m.mine)
                    .copyWith(fontStyle: FontStyle.italic, color: ChatText.meta(context, mine: m.mine).color)),
          ])
        : isImage
            ? ChatImage(url: m.mediaUrl!, blurred: !m.mine && !revealed, onReveal: onReveal)
            : Text(m.body ?? '', style: ChatText.body(context, mine: m.mine));
    const r = Radius.circular(Radii.lg);
    const joined = Radius.circular(6);
    final bubble = Container(
      constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.76),
      padding: isImage ? EdgeInsets.zero : const EdgeInsets.fromLTRB(14, 10, 14, 8),
      decoration: BoxDecoration(
        color: isImage ? Colors.transparent : (m.mine ? (m.failed ? c.danger : c.accent) : c.raised),
        borderRadius: BorderRadius.only(
          topLeft: !m.mine && groupedWithPrev ? joined : r,
          topRight: m.mine && groupedWithPrev ? joined : r,
          bottomLeft: !m.mine && groupedWithNext ? joined : (m.mine ? r : const Radius.circular(4)),
          bottomRight: m.mine && groupedWithNext ? joined : (m.mine ? const Radius.circular(4) : r),
        ),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.end, mainAxisSize: MainAxisSize.min, children: [
        content,
        SizedBox(height: isImage ? 5 : 3),
        Padding(padding: EdgeInsets.only(right: isImage ? 4 : 0), child: meta),
      ]),
    );
    return Align(
      alignment: m.mine ? Alignment.centerRight : Alignment.centerLeft,
      child: Padding(
        padding: EdgeInsets.only(top: groupedWithPrev ? 3 : Space.sm, left: m.mine ? 56 : 0, right: m.mine ? 0 : 56),
        child: GestureDetector(onLongPress: onLongPress, child: bubble),
      ),
    );
  }
}

/// Gambar di DM. Gambar dari lawan bicara tampil blur sampai diketuk "Lihat"; setelah itu ketuk untuk
/// membuka layar penuh (zoom, geser untuk tutup).
class ChatImage extends StatelessWidget {
  const ChatImage({super.key, required this.url, required this.blurred, required this.onReveal});

  final String url;
  final bool blurred;
  final VoidCallback onReveal;

  @override
  Widget build(BuildContext context) {
    final heroTag = 'chat-image:$url';
    final img = ClipRRect(
      borderRadius: Radii.all(Radii.lg),
      child: Image.network(
        url,
        width: 240,
        fit: BoxFit.cover,
        // Ukuran tetap selama memuat supaya gelembung tidak melompat dan tetap bisa diketuk.
        frameBuilder: (context, child, frame, sync) => frame == null && !sync
            ? Container(width: 240, height: 180, color: context.colors.overlay)
            : child,
        errorBuilder: (_, _, _) => const SizedBox(width: 240, height: 180, child: Icon(Icons.broken_image_outlined)),
      ),
    );
    if (blurred) {
      return GestureDetector(
        key: const Key('chat-image-reveal'),
        onTap: onReveal,
        child: Stack(alignment: Alignment.center, children: [
          ClipRRect(
            borderRadius: Radii.all(Radii.lg),
            child: ImageFiltered(imageFilter: ImageFilter.blur(sigmaX: 24, sigmaY: 24), child: img),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(color: const Color(0x8C000000), borderRadius: Radii.all(Radii.md)),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              const Icon(Icons.visibility_outlined, size: 16, color: OnImage.text),
              const SizedBox(width: 6),
              Text('Lihat foto', style: context.text.labelMedium?.copyWith(color: OnImage.text)),
            ]),
          ),
        ]),
      );
    }
    return GestureDetector(
      key: const Key('chat-image'),
      onTap: () => ImageViewer.open(context, image: NetworkImage(url), heroTag: heroTag),
      child: Hero(tag: heroTag, child: img),
    );
  }
}

class _DeliveryIcon extends StatelessWidget {
  const _DeliveryIcon(this.delivery, {required this.mine});

  final Delivery delivery;
  final bool mine;

  @override
  Widget build(BuildContext context) {
    final color = ChatText.meta(context, mine: mine).color;
    return switch (delivery) {
      Delivery.sending => Icon(Icons.schedule, size: 14, color: color, semanticLabel: 'mengirim'),
      Delivery.failed => Icon(Icons.error_outline_rounded, size: 14, color: color, semanticLabel: 'gagal'),
      Delivery.sent => Icon(Icons.check, size: 14, color: color, semanticLabel: 'tersimpan'),
      Delivery.delivered => Icon(Icons.done_all, size: 14, color: color, semanticLabel: 'sampai'),
      Delivery.read => Icon(Icons.done_all_rounded,
          size: 14, color: mine ? context.colors.onAccent : context.colors.accent, semanticLabel: 'dibaca'),
    };
  }
}
