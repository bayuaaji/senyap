import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

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
      final conv = await ref.read(chatRepositoryProvider).start(
            originType: widget.originType,
            originId: widget.originId,
            firstMessage: text,
            clientMsgId: _clientMsgId,
          );
      ref.invalidate(conversationsProvider('inbox'));
      if (mounted) context.pushReplacement('/pesan/${conv.id}');
    } on ApiError catch (e) {
      setState(() => _error = e.code == 'NOT_FOUND' ? 'Post ini sudah tidak bisa dibisiki.' : e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Bisik')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            if (widget.excerpt != null)
              Card(child: Padding(padding: const EdgeInsets.all(12), child: Text('"${widget.excerpt}"'))),
            const SizedBox(height: 8),
            const Text('Pesan pembukamu akan masuk ke tab Permintaan. Kamu bisa lanjut mengobrol setelah diterima. '
                'Identitasmu tetap tersembunyi; yang terlihat hanya aliasmu.'),
            const SizedBox(height: 12),
            TextField(
              key: const Key('opening'),
              controller: _text,
              minLines: 3,
              maxLines: 6,
              maxLength: 500,
              autofocus: true,
              decoration: InputDecoration(border: const OutlineInputBorder(), hintText: 'Tulis pesan pembuka…',
                  errorText: _error),
            ),
            const SizedBox(height: 12),
            FilledButton(
              key: const Key('send-whisper'),
              onPressed: _busy ? null : _submit,
              child: Text(_busy ? 'Mengirim…' : 'Kirim bisikan'),
            ),
          ]),
        ),
      ),
    );
  }
}
