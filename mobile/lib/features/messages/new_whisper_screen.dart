import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import 'chat_engine.dart';
import 'chat_providers.dart';
import 'chat_repository.dart';

/// Mulai Bisik dari post/balasan: `/bisik/baru?origin_type=POST&origin_id=…` (functional spec FS-05 §5.1).
/// Tombol **Bisik** di post (Fase 3, `openWhisper`) mengarah ke sini.
class NewWhisperScreen extends ConsumerStatefulWidget {
  const NewWhisperScreen({super.key, required this.originType, required this.originId, this.excerpt});

  final String originType;
  final String originId;
  final String? excerpt;

  @override
  ConsumerState<NewWhisperScreen> createState() => _NewWhisperScreenState();
}

class _NewWhisperScreenState extends ConsumerState<NewWhisperScreen> {
  final _text = TextEditingController();

  /// Sama di setiap percobaan ulang, supaya request tidak dibuat dua kali.
  final _clientMsgId = newClientMsgId();
  var _busy = false;
  String? _error;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final text = _text.text.trim();
    if (text.isEmpty) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final conv = await ref
          .read(chatRepositoryProvider)
          .start(
            originType: widget.originType,
            originId: widget.originId,
            firstMessage: text,
            clientMsgId: _clientMsgId,
          );
      ref.invalidate(conversationsProvider('inbox'));
      // `go`, bukan `pushReplacement`: layar ini ada di navigator root, sedangkan chat ada di dalam tab Pesan
      // (navigator lain). pushReplacement lintas navigator memicu assertion `keyReservation` di go_router.
      if (mounted) context.go('/pesan/${conv.id}');
    } on ApiError catch (e) {
      setState(() => _error = e.code == 'NOT_FOUND' ? 'Post ini sudah tidak bisa dibisiki.' : e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Scaffold(
      appBar: const SenyapBar(),
      body: SafeArea(
        top: false,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: ListView(
                keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
                padding: const EdgeInsets.fromLTRB(Space.gutter, Space.md, Space.gutter, Space.xl),
                children: [
                  Text('Mulai berbisik', style: context.text.headlineLarge),
                  const SizedBox(height: Space.sm),
                  Text(
                    'Pesan pembukamu masuk ke tab Permintaan miliknya. Obrolan terbuka setelah dia menerima. '
                    'Yang terlihat hanya aliasmu.',
                    style: context.text.bodyMedium,
                  ),
                  if (widget.excerpt != null) ...[
                    const SizedBox(height: Space.xl),
                    Container(
                      padding: const EdgeInsets.fromLTRB(Space.md, Space.sm, Space.md, Space.md),
                      decoration: BoxDecoration(
                        borderRadius: Radii.all(Radii.md),
                        border: Border.all(color: c.line, width: 1),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            widget.originType == 'REPLY' ? 'Dari balasan' : 'Dari cerita',
                            style: context.text.labelMedium?.copyWith(color: c.textTertiary),
                          ),
                          const SizedBox(height: Space.xxs),
                          Text(
                            '"${widget.excerpt}"',
                            style: PostType.quote.copyWith(color: c.text),
                          ),
                        ],
                      ),
                    ),
                  ],
                  const SizedBox(height: Space.xl),
                  TextField(
                    key: const Key('opening'),
                    controller: _text,
                    minLines: 4,
                    maxLines: 8,
                    maxLength: 500,
                    autofocus: true,
                    textCapitalization: TextCapitalization.sentences,
                    style: context.text.bodyLarge,
                    decoration: InputDecoration(
                      labelText: 'Pesan pembuka',
                      alignLabelWithHint: true,
                      hintText: 'Sapa dengan hangat. Misalnya, bagian mana dari ceritanya yang menyentuhmu.',
                      hintMaxLines: 3,
                      errorText: _error,
                      counterStyle: context.text.labelSmall?.copyWith(color: c.textTertiary),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(Space.gutter, Space.sm, Space.gutter, Space.md),
              child: PrimaryButton(
                key: const Key('send-whisper'),
                tone: ButtonTone.accent,
                busy: _busy,
                label: 'Kirim bisikan',
                onPressed: _submit,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
