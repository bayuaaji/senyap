import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../posts/blurhash.dart';
import '../posts/location_service.dart';
import '../posts/post_card.dart';
import '../posts/post_models.dart';
import '../posts/posts_repository.dart';
import 'background_picker.dart';

/// Editor post (functional spec FS-02): teks 1–250 karakter, kategori, latar dari galeri bawaan atau
/// upload sendiri, lokasi opsional (geohash, ±5 km). Pratinjau langsung seperti kartu di feed.
class ComposeScreen extends ConsumerStatefulWidget {
  const ComposeScreen({super.key});

  @override
  ConsumerState<ComposeScreen> createState() => _ComposeScreenState();
}

class _ComposeScreenState extends ConsumerState<ComposeScreen> {
  static const maxLength = 250;

  final _text = TextEditingController();
  String _category = 'curhat';
  List<GalleryItem> _gallery = const [];
  GalleryItem? _background;
  bool _loadingGallery = false;
  bool _uploading = false;
  bool _useLocation = false;
  bool _posting = false;
  String? _galleryError;

  PostsRepository get _repo => ref.read(postsRepositoryProvider);

  @override
  void initState() {
    super.initState();
    _text.addListener(() => setState(() {}));
    _loadGallery();
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _loadGallery() async {
    setState(() {
      _loadingGallery = true;
      _galleryError = null;
    });
    try {
      final page = await _repo.backgrounds(_category);
      if (!mounted) return;
      setState(() {
        _gallery = page.items;
        if (_background == null || !_isUploaded(_background!)) {
          _background = page.items.isEmpty ? null : page.items.first;
        }
      });
    } catch (e) {
      if (mounted) setState(() => _galleryError = ApiError.from(e).message);
    } finally {
      if (mounted) setState(() => _loadingGallery = false);
    }
  }

  final _uploaded = <String>{};

  bool _isUploaded(GalleryItem item) => _uploaded.contains(item.id);

  Future<void> _upload() async {
    final picked = await ref.read(backgroundPickerProvider).pick();
    if (picked == null || !mounted) return;
    setState(() => _uploading = true);
    try {
      final item = await _repo.uploadBackground(picked.bytes, picked.mimeType);
      if (!mounted) return;
      setState(() {
        _uploaded.add(item.id);
        _background = item;
      });
    } catch (e) {
      _toast(ApiError.from(e).message);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  bool get _canPost {
    final len = _text.text.trim().characters.length;
    return !_posting && !_uploading && _background != null && len >= 1 && len <= maxLength;
  }

  Future<void> _post() async {
    if (!_canPost) return;
    setState(() => _posting = true);
    try {
      String? cell;
      if (_useLocation) {
        cell = await ref.read(locationServiceProvider).currentCell();
        if (cell == null) {
          _toast('Lokasi tidak tersedia. Post dikirim tanpa lokasi.');
        }
      }
      final post = await _repo.create(
        body: _text.text.trim(),
        category: _category,
        backgroundId: _background!.id,
        geohash: cell,
      );
      if (!mounted) return;
      _text.clear();
      setState(() => _useLocation = false);
      context.push('/post/${post.id}', extra: post);
    } catch (e) {
      _toast(ApiError.from(e).message);
    } finally {
      if (mounted) setState(() => _posting = false);
    }
  }

  void _toast(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final length = _text.text.trim().characters.length;
    final bg = _background;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Tulis'),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: FilledButton(
              key: const Key('compose-submit'),
              onPressed: _canPost ? _post : null,
              child: Text(_posting ? 'Mengirim…' : 'Kirim'),
            ),
          ),
        ],
      ),
      body: ListView(key: const Key('compose-list'), padding: const EdgeInsets.only(bottom: 24), children: [
        // Pratinjau: teks di atas latar, persis seperti di feed.
        Padding(
          padding: const EdgeInsets.all(12),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: AspectRatio(
              aspectRatio: 4 / 5,
              child: Stack(fit: StackFit.expand, children: [
                PostBackground(background: bg == null ? null : BackgroundImage(url: bg.url, blurhash: bg.blurhash)),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 40),
                  child: Center(
                    child: TextField(
                      key: const Key('compose-text'),
                      controller: _text,
                      maxLines: null,
                      textAlign: TextAlign.center,
                      cursorColor: Colors.white,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 22,
                        fontWeight: FontWeight.w600,
                        shadows: [Shadow(blurRadius: 8, color: Colors.black87)],
                      ),
                      decoration: const InputDecoration(
                        border: InputBorder.none,
                        hintText: 'Ceritakan sesuatu tanpa nama…',
                        hintStyle: TextStyle(color: Colors.white70),
                      ),
                    ),
                  ),
                ),
                if (_uploading) const ColoredBox(color: Color(0x66000000), child: Center(child: CircularProgressIndicator())),
              ]),
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Align(
            alignment: Alignment.centerRight,
            child: Text('$length/$maxLength',
                style: TextStyle(color: length > maxLength ? Theme.of(context).colorScheme.error : null)),
          ),
        ),
        const _SectionTitle('Kategori'),
        SizedBox(
          height: 48,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            children: [
              for (final e in postCategories.entries)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    label: Text(e.value),
                    selected: e.key == _category,
                    onSelected: (_) {
                      setState(() => _category = e.key);
                      _loadGallery();
                    },
                  ),
                ),
            ],
          ),
        ),
        const _SectionTitle('Latar'),
        SizedBox(
          height: 96,
          child: _galleryError != null
              ? Center(child: TextButton(onPressed: _loadGallery, child: Text('$_galleryError Coba lagi')))
              : ListView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  children: [
                    _Thumb(
                      key: const Key('compose-upload'),
                      selected: false,
                      onTap: _uploading ? null : _upload,
                      child: const Center(child: Icon(Icons.add_photo_alternate_outlined)),
                    ),
                    if (bg != null && _isUploaded(bg))
                      _Thumb(selected: true, onTap: () {}, child: _ThumbImage(bg)),
                    if (_loadingGallery) const Center(child: Padding(padding: EdgeInsets.all(16), child: CircularProgressIndicator())),
                    for (final item in _gallery)
                      _Thumb(
                        selected: bg?.id == item.id,
                        onTap: () => setState(() => _background = item),
                        child: _ThumbImage(item),
                      ),
                  ],
                ),
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(16, 4, 16, 0),
          child: Text('Foto yang kamu unggah dikaburkan sedikit dan digelapkan supaya tulisan terbaca dan wajah sulit dikenali.',
              style: TextStyle(fontSize: 12)),
        ),
        SwitchListTile(
          key: const Key('compose-location'),
          value: _useLocation,
          onChanged: (v) => setState(() => _useLocation = v),
          title: const Text('Tampilkan kota'),
          subtitle: const Text('Hanya kota dan area ±5 km yang disimpan, bukan lokasi tepatmu.'),
        ),
      ]),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
        child: Text(text, style: Theme.of(context).textTheme.titleSmall),
      );
}

class _Thumb extends StatelessWidget {
  const _Thumb({super.key, required this.selected, required this.onTap, required this.child});

  final bool selected;
  final VoidCallback? onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Container(
          width: 72,
          height: 90,
          clipBehavior: Clip.antiAlias,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              width: 3,
              color: selected ? Theme.of(context).colorScheme.primary : Colors.transparent,
            ),
          ),
          child: child,
        ),
      ),
    );
  }
}

class _ThumbImage extends StatelessWidget {
  const _ThumbImage(this.item);

  final GalleryItem item;

  @override
  Widget build(BuildContext context) => Stack(fit: StackFit.expand, children: [
        BlurHashBox(hash: item.blurhash),
        Image.network(item.url, fit: BoxFit.cover, errorBuilder: (_, _, _) => const SizedBox.shrink()),
      ]);
}
