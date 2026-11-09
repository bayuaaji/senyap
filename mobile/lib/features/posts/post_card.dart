import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/ui/ui.dart';
import '../../core/time/relative_time.dart';
import '../profile/alias_avatar.dart';
import 'blurhash.dart';
import 'post_models.dart';

/// Avatar penulis (emoji atau inisial di lingkaran warna alias); abu-abu untuk akun yang sudah dihapus.
class AuthorAvatar extends StatelessWidget {
  const AuthorAvatar({super.key, required this.author, this.radius = 14});

  final Author author;
  final double radius;

  @override
  Widget build(BuildContext context) =>
      IdentityAvatar(name: author.name, colorHex: author.color, emoji: author.avatar, radius: radius);
}

/// Latar post: BlurHash dulu, gambar asli memudar masuk setelah termuat.
class PostBackground extends StatelessWidget {
  const PostBackground({super.key, required this.background});

  final BackgroundImage? background;

  @override
  Widget build(BuildContext context) {
    final bg = background;
    return Stack(
      fit: StackFit.expand,
      children: [
        BlurHashBox(hash: bg?.blurhash),
        if (bg != null)
          Image.network(
            bg.url,
            fit: BoxFit.cover,
            frameBuilder: (context, child, frame, sync) => AnimatedOpacity(
              opacity: sync || frame != null ? 1 : 0,
              duration: Motion.slow,
              curve: Motion.curve,
              child: child,
            ),
            errorBuilder: (context, error, stack) => const SizedBox.shrink(),
          ),
      ],
    );
  }
}

/// Peredup merata di atas foto supaya teks putih terbaca di bagian mana pun (DESIGN.md, kontras).
class PostScrim extends StatelessWidget {
  const PostScrim({super.key});

  @override
  Widget build(BuildContext context) => const IgnorePointer(child: ColoredBox(color: OnImage.scrim));
}

/// Teks post di tengah gambar: serif, ukuran menyesuaikan panjang.
class PostText extends StatelessWidget {
  const PostText(this.body, {super.key, this.kicker});

  final String body;

  /// Di atas teks (kategori).
  final Widget? kicker;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(Space.xl, Space.x3, Space.xl, Space.x3),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (kicker != null) ...[kicker!, const SizedBox(height: Space.md)],
            Flexible(
              child: Text(body, textAlign: TextAlign.center, style: PostType.body(body.characters.length)),
            ),
          ],
        ),
      ),
    );
  }
}

/// Kategori di atas teks cerita: teks kecil polos dengan garis pendek di bawahnya.
class PostKicker extends StatelessWidget {
  const PostKicker({super.key, required this.label});

  final String label;

  @override
  Widget build(BuildContext context) => Column(mainAxisSize: MainAxisSize.min, children: [
        Text(
          label,
          style: context.text.labelMedium?.copyWith(color: OnImage.soft, shadows: OnImage.shadow, letterSpacing: 0.2),
        ),
        const SizedBox(height: Space.xs),
        Container(width: 16, height: 1.5, color: OnImage.faint),
      ]);
}

/// Post (functional spec FS-02): baris penulis (alias, kota, waktu, menu) di atas foto 4:5 berisi teks
/// serif, lalu baris aksi (hati, balasan, Bisik). Info dan aksi duduk di luar foto supaya tulisan jadi fokus.
class PostCard extends StatelessWidget {
  const PostCard({
    super.key,
    required this.post,
    this.onTap,
    this.onHeart,
    this.onWhisper,
    this.onMore,
    this.clock = DateTime.now,
    this.heroTag,
    this.margin = const EdgeInsets.symmetric(horizontal: Space.md),
  });

  final Post post;
  final VoidCallback? onTap;
  final VoidCallback? onHeart;
  final VoidCallback? onWhisper;
  final VoidCallback? onMore;
  final DateTime Function() clock;

  /// Hero foto → detail. `null` = tanpa Hero.
  final Object? heroTag;
  final EdgeInsetsGeometry margin;

  /// Jarak isi baris penulis/aksi dari tepi foto, supaya teksnya sejajar gutter layar.
  static const inset = Space.xs;

  static String heroTagFor(String postId) => 'post-card:$postId';

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final meta = DefaultTextStyle(
      maxLines: 1,
      softWrap: false,
      overflow: TextOverflow.ellipsis,
      style: context.text.bodySmall!.copyWith(color: c.textTertiary, fontWeight: FontWeight.w400),
      child: Row(
        children: [
          if (post.city != null) ...[
            Flexible(child: Text(post.city!, maxLines: 1, overflow: TextOverflow.ellipsis)),
            const Text(' · '),
          ],
          Flexible(child: RelativeTimeText(post.createdAt, clock: clock)),
        ],
      ),
    );

    final visual = ClipRRect(
      borderRadius: Radii.all(Radii.lg),
      child: Stack(
        fit: StackFit.expand,
        children: [
          PostBackground(background: post.background),
          const PostScrim(),
          PostText(
            post.body,
            kicker: PostKicker(key: const Key('post-category'), label: postCategories[post.category] ?? post.category),
          ),
        ],
      ),
    );

    return Card(
      margin: margin,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Penulis.
          Padding(
            padding: EdgeInsets.fromLTRB(inset, 0, onMore != null ? 0 : inset, Space.sm),
            child: Row(
              children: [
                AuthorAvatar(author: post.author, radius: 18),
                const SizedBox(width: Space.sm),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      AliasName(text: post.author.display, gender: post.author.gender, style: context.text.titleSmall),
                      const SizedBox(height: 1),
                      meta,
                    ],
                  ),
                ),
                if (onMore != null)
                  IconButton(
                    key: const Key('post-more'),
                    tooltip: 'Lainnya',
                    onPressed: onMore,
                    icon: Icon(Icons.more_horiz_rounded, color: c.textSecondary),
                  ),
              ],
            ),
          ),
          // Foto + teks. Ketuk untuk membuka cerita dan balasannya.
          Semantics(
            button: onTap != null,
            label: onTap != null ? 'Buka cerita' : null,
            child: Pressable(
              onTap: onTap,
              scale: 0.99,
              child: AspectRatio(
                aspectRatio: 4 / 5,
                child: heroTag == null
                    ? visual
                    : Hero(tag: heroTag!, child: Material(type: MaterialType.transparency, child: visual)),
              ),
            ),
          ),
          // Aksi.
          Padding(
            padding: const EdgeInsets.only(top: Space.xxs),
            child: Row(
              children: [
                _CountAction(
                  key: const Key('post-heart'),
                  tooltip: post.viewerHasHearted ? 'Batal hati' : 'Beri hati',
                  onTap: onHeart,
                  icon: post.viewerHasHearted ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                  active: post.viewerHasHearted,
                  count: post.heartCount,
                ),
                _CountAction(
                  tooltip: 'Balasan',
                  onTap: onTap,
                  icon: Icons.mode_comment_outlined,
                  count: post.replyCount,
                ),
                const Spacer(),
                if (post.canWhisper && onWhisper != null)
                  _WhisperButton(key: const Key('post-whisper'), onTap: onWhisper!),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _CountAction extends StatelessWidget {
  const _CountAction({
    super.key,
    required this.icon,
    required this.count,
    this.onTap,
    this.active = false,
    this.tooltip,
  });

  final IconData icon;
  final int count;
  final VoidCallback? onTap;
  final bool active;
  final String? tooltip;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Tooltip(
      message: tooltip ?? '',
      child: InkWell(
        borderRadius: Radii.all(Radii.md),
        onTap: onTap == null
            ? null
            : () {
                HapticFeedback.lightImpact();
                onTap!();
              },
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 44, minWidth: 44),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: Space.xs),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                AnimatedSwitcher(
                  duration: Motion.of(context, Motion.base),
                  transitionBuilder: (child, a) => ScaleTransition(
                    scale: Tween(begin: 0.7, end: 1.0).animate(CurvedAnimation(parent: a, curve: Curves.easeOutBack)),
                    child: child,
                  ),
                  child: Icon(icon, key: ValueKey(active), size: 23, color: active ? c.accent : c.textSecondary),
                ),
                const SizedBox(width: 6),
                Text('$count', style: context.text.labelMedium?.copyWith(color: c.textSecondary, fontSize: 14)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Tombol Bisik: satu-satunya aksen jambu di post (Bisik adalah inti Senyap).
class _WhisperButton extends StatelessWidget {
  const _WhisperButton({super.key, required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Semantics(
      button: true,
      label: 'Bisik ke penulis',
      excludeSemantics: true,
      child: Pressable(
        onTap: onTap,
        haptic: true,
        scale: 0.95,
        child: Container(
          height: 40,
          margin: const EdgeInsets.only(right: PostCard.inset - 4),
          padding: const EdgeInsets.symmetric(horizontal: 14),
          decoration: BoxDecoration(color: c.accentSoft, borderRadius: Radii.all(Radii.md)),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.lock_outline_rounded, size: 16, color: c.accent),
              const SizedBox(width: 6),
              Text('Bisik', style: context.text.labelLarge?.copyWith(color: c.accent, fontSize: 14.5)),
            ],
          ),
        ),
      ),
    );
  }
}

/// Skeleton post saat memuat feed: mengikuti bentuk post sungguhan.
class PostCardSkeleton extends StatelessWidget {
  const PostCardSkeleton({super.key});

  @override
  Widget build(BuildContext context) => const Padding(
    padding: EdgeInsets.symmetric(horizontal: Space.md),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsets.fromLTRB(PostCard.inset, 0, PostCard.inset, Space.sm),
          child: Row(
            children: [
              SkeletonBox(height: 36, circle: true),
              SizedBox(width: Space.sm),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SkeletonBox(width: 140, height: 12),
                  SizedBox(height: 6),
                  SkeletonBox(width: 90, height: 10),
                ],
              ),
            ],
          ),
        ),
        AspectRatio(aspectRatio: 4 / 5, child: SkeletonBox(height: double.infinity, radius: Radii.lg)),
      ],
    ),
  );
}

/// Kartu ringkas untuk feed dua kolom: foto berisi potongan teks (tinggi mengikuti panjang teks), lalu
/// penulis, kota · waktu, dan aksi (hati, balasan, Bisik). Ketuk foto untuk membuka cerita.
class PostTile extends StatelessWidget {
  const PostTile({
    super.key,
    required this.post,
    this.onTap,
    this.onHeart,
    this.onWhisper,
    this.onMore,
    this.clock = DateTime.now,
    this.heroTag,
  });

  final Post post;
  final VoidCallback? onTap;
  final VoidCallback? onHeart;
  final VoidCallback? onWhisper;
  final VoidCallback? onMore;
  final DateTime Function() clock;
  final Object? heroTag;

  /// Rasio lebar:tinggi foto menurut panjang teks, supaya kolom kiri-kanan tidak seragam (masonry).
  static double aspectFor(int length) => switch (length) {
        <= 50 => 1.0,
        <= 120 => 0.8,
        _ => 0.66,
      };

  /// Perkiraan tinggi tile untuk membagi post ke kolom yang lebih pendek.
  static double estimateHeight(Post post, double width) => width / aspectFor(post.body.characters.length) + 112;

  static double _fontFor(int length) => switch (length) {
        <= 50 => 20,
        <= 120 => 17,
        _ => 15.5,
      };

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final length = post.body.characters.length;
    final size = _fontFor(length);
    final visual = ClipRRect(
      borderRadius: Radii.all(Radii.lg - 4),
      child: Stack(fit: StackFit.expand, children: [
        PostBackground(background: post.background),
        const PostScrim(),
        Padding(
          padding: const EdgeInsets.all(Space.sm),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(
              postCategories[post.category] ?? post.category,
              key: const Key('post-category'),
              style: context.text.labelSmall?.copyWith(color: OnImage.soft, shadows: OnImage.shadow),
            ),
            const SizedBox(height: Space.xs),
            Expanded(
              child: LayoutBuilder(builder: (context, box) {
                final style = PostType.body(length).copyWith(fontSize: size, height: 1.28, letterSpacing: -0.1);
                final lines = (box.maxHeight / (size * 1.28)).floor().clamp(1, 12);
                return Align(
                  alignment: Alignment.bottomLeft,
                  child: Text(post.body, maxLines: lines, overflow: TextOverflow.ellipsis, style: style),
                );
              }),
            ),
          ]),
        ),
      ]),
    );

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
      Semantics(
        button: onTap != null,
        label: onTap != null ? 'Buka cerita' : null,
        child: Pressable(
          onTap: onTap,
          scale: 0.98,
          child: AspectRatio(
            aspectRatio: aspectFor(length),
            child: heroTag == null
                ? visual
                : Hero(tag: heroTag!, child: Material(type: MaterialType.transparency, child: visual)),
          ),
        ),
      ),
      const SizedBox(height: Space.xs),
      // Penulis + menu. Tinggi tetap supaya kartu dengan dan tanpa ⋯ sejajar.
      SizedBox(height: 32, child: Row(children: [
        AuthorAvatar(author: post.author, radius: 11),
        const SizedBox(width: 6),
        Expanded(
          child: AliasName(
            text: post.author.display,
            gender: post.author.gender,
            style: context.text.labelMedium?.copyWith(color: c.text),
          ),
        ),
        if (onMore != null)
          SizedBox.square(
            dimension: 32,
            child: IconButton(
              key: const Key('post-more'),
              tooltip: 'Lainnya',
              padding: EdgeInsets.zero,
              style: const ButtonStyle(minimumSize: WidgetStatePropertyAll(Size(32, 32))),
              onPressed: onMore,
              icon: Icon(Icons.more_horiz_rounded, size: 18, color: c.textTertiary),
            ),
          ),
      ])),
      // Kota · waktu.
      DefaultTextStyle(
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        softWrap: false,
        style: context.text.bodySmall!.copyWith(color: c.textTertiary, fontWeight: FontWeight.w400, fontSize: 12),
        child: Row(children: [
          if (post.city != null) ...[
            Flexible(child: Text(post.city!)),
            const Text(' · '),
          ],
          Flexible(child: RelativeTimeText(post.createdAt, clock: clock)),
        ]),
      ),
      // Aksi. Di kartu sempit (HP kecil) tombol Bisik tampil sebagai ikon saja supaya tidak meluber.
      LayoutBuilder(builder: (context, box) {
        final narrow = box.maxWidth < 172;
        return Row(children: [
          _TileCount(
            key: const Key('post-heart'),
            tooltip: post.viewerHasHearted ? 'Batal hati' : 'Beri hati',
            icon: post.viewerHasHearted ? Icons.favorite_rounded : Icons.favorite_border_rounded,
            active: post.viewerHasHearted,
            count: post.heartCount,
            onTap: onHeart,
          ),
          _TileCount(tooltip: 'Balasan', icon: Icons.mode_comment_outlined, count: post.replyCount, onTap: onTap),
          const Spacer(),
          if (post.canWhisper && onWhisper != null)
            Tooltip(
              message: 'Bisik ke penulis',
              child: Semantics(
                button: true,
                label: 'Bisik ke penulis',
                excludeSemantics: true,
                child: Pressable(
                  key: const Key('post-whisper'),
                  onTap: onWhisper,
                  haptic: true,
                  scale: 0.95,
                  child: Container(
                    height: 36,
                    constraints: const BoxConstraints(minWidth: 36),
                    padding: EdgeInsets.symmetric(horizontal: narrow ? 0 : 10),
                    decoration: BoxDecoration(color: c.accentSoft, borderRadius: Radii.all(Radii.sm + 2)),
                    child: Row(mainAxisSize: MainAxisSize.min, mainAxisAlignment: MainAxisAlignment.center, children: [
                      Icon(Icons.lock_outline_rounded, size: 15, color: c.accent),
                      if (!narrow) ...[
                        const SizedBox(width: 4),
                        Text('Bisik', style: context.text.labelMedium?.copyWith(color: c.accent)),
                      ],
                    ]),
                  ),
                ),
              ),
            ),
        ]);
      }),
    ]);
  }
}

class _TileCount extends StatelessWidget {
  const _TileCount({super.key, required this.icon, required this.count, this.onTap, this.active = false, this.tooltip});

  final IconData icon;
  final int count;
  final VoidCallback? onTap;
  final bool active;
  final String? tooltip;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Tooltip(
      message: tooltip ?? '',
      child: InkWell(
        borderRadius: Radii.all(Radii.sm),
        onTap: onTap == null
            ? null
            : () {
                HapticFeedback.lightImpact();
                onTap!();
              },
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 40, minWidth: 36),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 18, color: active ? c.accent : c.textSecondary),
            const SizedBox(width: 3),
            Text('$count', style: context.text.labelMedium?.copyWith(color: c.textSecondary)),
            const SizedBox(width: 6),
          ]),
        ),
      ),
    );
  }
}

/// Skeleton tile saat memuat feed dua kolom.
class PostTileSkeleton extends StatelessWidget {
  const PostTileSkeleton({super.key, this.aspect = 0.8});

  final double aspect;

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        AspectRatio(aspectRatio: aspect, child: const SkeletonBox(height: double.infinity, radius: Radii.lg - 4)),
        const SizedBox(height: Space.xs),
        const Row(children: [
          SkeletonBox(height: 22, circle: true),
          SizedBox(width: 6),
          SkeletonBox(width: 90, height: 10),
        ]),
        const SizedBox(height: 6),
        const SkeletonBox(width: 70, height: 9),
      ]);
}
