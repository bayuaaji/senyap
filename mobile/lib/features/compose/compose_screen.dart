import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/shell.dart' show navClearance;
import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../posts/blurhash.dart';
import '../posts/location_prompt.dart';
import '../posts/location_service.dart';
import '../posts/post_card.dart';
import '../posts/post_models.dart';
import '../posts/posts_repository.dart';
import '../safety/safety_actions.dart';
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
        final location = await _resolveLocation();
        if (location == null) return; // dibatalkan atau user ke Pengaturan dulu
        cell = location.cell;
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
      if (post.supportCard) showSupportCard(context, ref);
    } catch (e) {
      _toast(ApiError.from(e).message);
    } finally {
      if (mounted) setState(() => _posting = false);
    }
  }

  /// Lokasi untuk post. `null` = jangan kirim sekarang; [LocationResult] tanpa cell = kirim tanpa lokasi
  /// (hanya bila user memilihnya sendiri, tidak pernah diam-diam).
  Future<LocationResult?> _resolveLocation() async {
    final service = ref.read(locationServiceProvider);
    while (true) {
      final result = await service.currentCell();
      if (result.isOk || !mounted) return result;
      final choice = await showLocationProblem(context, result.status, withoutLabel: 'Kirim tanpa lokasi');
      switch (choice) {
        case LocationChoice.retry:
          continue;
        case LocationChoice.openSettings:
          await openSettingsFor(service, result.status);
          return null;
        case LocationChoice.continueWithout:
          return const LocationResult(LocationStatus.failed);
        case LocationChoice.cancel:
          return null;
      }
    }
  }

  void _toast(String message) {
    if (!mounted) return;
    showToast(context, message, tone: ToastTone.error);
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final length = _text.text.trim().characters.length;
    final bg = _background;
    final over = length > maxLength;
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            ScreenTitle(
              'Tulis',
              subtitle: 'Yang membaca hanya melihat aliasmu.',
              trailing: FilledButton(
                key: const Key('compose-submit'),
                style: FilledButton.styleFrom(minimumSize: const Size(84, 44)),
                onPressed: _canPost ? _post : null,
                child: Text(_posting ? 'Mengirim…' : 'Kirim'),
              ),
            ),
            Expanded(
              child: ListView(
                key: const Key('compose-list'),
                padding: EdgeInsets.only(bottom: navClearance(context)),
                keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
                children: [
                  // Pratinjau: teks di atas latar, persis seperti di feed.
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: Space.md),
                    child: ClipRRect(
                      borderRadius: Radii.all(Radii.lg),
                      child: AspectRatio(
                        aspectRatio: 4 / 5,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            AnimatedSwitcher(
                              duration: Motion.slow,
                              child: PostBackground(
                                key: ValueKey(bg?.id),
                                background: bg == null ? null : BackgroundImage(url: bg.url, blurhash: bg.blurhash),
                              ),
                            ),
                            const PostScrim(),
                            Padding(
                              padding: const EdgeInsets.fromLTRB(Space.xl, Space.x3, Space.xl, Space.x3),
                              child: Center(
                                child: Column(mainAxisSize: MainAxisSize.min, children: [
                                PostKicker(label: postCategories[_category] ?? _category),
                                const SizedBox(height: Space.md),
                                Flexible(child: TextField(
                                  key: const Key('compose-text'),
                                  controller: _text,
                                  maxLines: null,
                                  textAlign: TextAlign.center,
                                  textCapitalization: TextCapitalization.sentences,
                                  cursorColor: OnImage.text,
                                  style: PostType.body(length == 0 ? 1 : length),
                                  decoration: InputDecoration(
                                    filled: false,
                                    border: InputBorder.none,
                                    enabledBorder: InputBorder.none,
                                    focusedBorder: InputBorder.none,
                                    hintText: 'Tulis yang sedang kamu pikirkan.',
                                    hintMaxLines: 3,
                                    hintStyle: PostType.body(40, color: OnImage.faint),
                                  ),
                                )),
                                ]),
                              ),
                            ),
                            Positioned(
                              right: Space.md,
                              bottom: Space.sm,
                              child: Text(
                                over ? '$length/$maxLength, terlalu panjang' : '$length/$maxLength',
                                style: context.text.labelSmall?.copyWith(
                                  color: OnImage.text,
                                  shadows: OnImage.shadow,
                                  decoration: over ? TextDecoration.underline : null,
                                  decorationColor: OnImage.text,
                                ),
                              ),
                            ),
                            if (_uploading)
                              const ColoredBox(
                                color: Color(0x80000000),
                                child: Loader(color: OnImage.text),
                              ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  const SectionLabel('Kategori'),
                  ChoiceChips<String>(
                    options: postCategories,
                    selected: _category,
                    onSelected: (k) {
                      setState(() => _category = k);
                      _loadGallery();
                    },
                  ),
                  const SectionLabel('Latar'),
                  SizedBox(
                    height: 100,
                    child: _galleryError != null
                        ? Center(
                            child: TextButton(onPressed: _loadGallery, child: Text('$_galleryError Coba lagi')),
                          )
                        : ListView(
                            scrollDirection: Axis.horizontal,
                            padding: Space.screen,
                            children: [
                              _Thumb(
                                key: const Key('compose-upload'),
                                selected: false,
                                onTap: _uploading ? null : _upload,
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Icon(Icons.add_photo_alternate_outlined, color: c.text),
                                    const SizedBox(height: 6),
                                    Text('Fotomu', style: context.text.labelSmall?.copyWith(color: c.textSecondary)),
                                  ],
                                ),
                              ),
                              if (bg != null && _isUploaded(bg))
                                _Thumb(selected: true, onTap: () {}, child: _ThumbImage(bg)),
                              if (_loadingGallery) const SizedBox(width: 80, child: Loader()),
                              for (final item in _gallery)
                                _Thumb(
                                  selected: bg?.id == item.id,
                                  onTap: () {
                                    HapticFeedback.selectionClick();
                                    setState(() => _background = item);
                                  },
                                  child: _ThumbImage(item),
                                ),
                            ],
                          ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(Space.gutter, Space.sm, Space.gutter, 0),
                    child: Text(
                      'Foto yang kamu unggah dikaburkan sedikit dan digelapkan supaya tulisan terbaca dan wajah sulit dikenali.',
                      style: context.text.bodySmall?.copyWith(color: c.textTertiary, fontWeight: FontWeight.w400),
                    ),
                  ),
                  const SizedBox(height: Space.lg),
                  Divider(color: c.line),
                  GroupCard(
                    children: [
                      GroupTile(
                        key: const Key('compose-location'),
                        title: 'Tampilkan kota',
                        subtitle: 'Hanya kota dan area ±5 km yang disimpan, bukan lokasi tepatmu.',
                        onTap: () => setState(() => _useLocation = !_useLocation),
                        trailing: Switch(value: _useLocation, onChanged: (v) => setState(() => _useLocation = v)),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({super.key, required this.selected, required this.onTap, required this.child});

  final bool selected;
  final VoidCallback? onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Padding(
      padding: const EdgeInsets.only(right: Space.xs),
      child: Pressable(
        onTap: onTap,
        scale: 0.94,
        child: AnimatedContainer(
          duration: Motion.base,
          curve: Motion.curve,
          width: 78,
          height: 100,
          padding: EdgeInsets.all(selected ? 3 : 0),
          decoration: BoxDecoration(
            color: c.raised,
            borderRadius: Radii.all(Radii.md),
            border: Border.all(width: 2, color: selected ? c.text : Colors.transparent),
          ),
          child: ClipRRect(borderRadius: Radii.all(selected ? Radii.md - 3 : Radii.md), child: child),
        ),
      ),
    );
  }
}

class _ThumbImage extends StatelessWidget {
  const _ThumbImage(this.item);

  final GalleryItem item;

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      BlurHashBox(hash: item.blurhash),
      Image.network(item.url, fit: BoxFit.cover, errorBuilder: (_, _, _) => const SizedBox.shrink()),
    ],
  );
}
