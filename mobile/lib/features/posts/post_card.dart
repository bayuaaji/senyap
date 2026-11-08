import 'package:flutter/material.dart';

import '../../core/time/relative_time.dart';
import '../profile/alias_avatar.dart' show aliasColor;
import 'blurhash.dart';
import 'post_models.dart';

/// Lingkaran berwarna alias; abu-abu untuk akun yang sudah dihapus.
class AuthorAvatar extends StatelessWidget {
  const AuthorAvatar({super.key, required this.author, this.radius = 14});

  final Author author;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final bg = author.color == null ? Colors.grey : aliasColor(author.color!);
    final fg = bg.computeLuminance() > 0.5 ? Colors.black87 : Colors.white;
    return CircleAvatar(
      radius: radius,
      backgroundColor: bg,
      child: Text(
        author.name.characters.first,
        style: TextStyle(color: fg, fontSize: radius * 0.85),
      ),
    );
  }
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
              duration: const Duration(milliseconds: 250),
              child: child,
            ),
            errorBuilder: (context, error, stack) => const SizedBox.shrink(),
          ),
      ],
    );
  }
}

/// Teks post di tengah gambar: putih tebal dengan bayangan supaya selalu terbaca.
class PostText extends StatelessWidget {
  const PostText(this.body, {super.key});

  final String body;

  @override
  Widget build(BuildContext context) {
    final size = body.length <= 80 ? 24.0 : (body.length <= 160 ? 20.0 : 17.0);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 56),
      child: Center(
        child: Text(
          body,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: Colors.white,
            fontSize: size,
            height: 1.35,
            fontWeight: FontWeight.w600,
            shadows: const [
              Shadow(blurRadius: 8, color: Colors.black87),
              Shadow(blurRadius: 2, color: Colors.black54),
            ],
          ),
        ),
      ),
    );
  }
}

/// Kartu post (functional spec FS-02): teks di atas gambar latar, alias penulis, kota, waktu relatif,
/// hati, jumlah balasan, tombol Bisik.
class PostCard extends StatelessWidget {
  const PostCard({
    super.key,
    required this.post,
    this.onTap,
    this.onHeart,
    this.onWhisper,
    this.onMore,
    this.clock = DateTime.now,
  });

  final Post post;
  final VoidCallback? onTap;
  final VoidCallback? onHeart;
  final VoidCallback? onWhisper;
  final VoidCallback? onMore;
  final DateTime Function() clock;

  static const _light = TextStyle(
    color: Colors.white,
    shadows: [Shadow(blurRadius: 4, color: Colors.black54)],
  );

  @override
  Widget build(BuildContext context) {
    return Card(
      clipBehavior: Clip.antiAlias,
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: InkWell(
        onTap: onTap,
        child: AspectRatio(
          aspectRatio: 4 / 5,
          child: Stack(
            fit: StackFit.expand,
            children: [
              PostBackground(background: post.background),
              PostText(post.body),
              Positioned(
                left: 12,
                right: 4,
                top: 8,
                child: Row(
                  children: [
                    AuthorAvatar(author: post.author),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        post.author.display,
                        style: _light.copyWith(fontWeight: FontWeight.w600),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    Text(postCategories[post.category] ?? post.category, style: _light.copyWith(fontSize: 12)),
                    if (onMore != null)
                      IconButton(
                        tooltip: 'Lainnya',
                        onPressed: onMore,
                        icon: const Icon(Icons.more_vert, color: Colors.white),
                      ),
                  ],
                ),
              ),
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: DecoratedBox(
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.bottomCenter,
                      end: Alignment.topCenter,
                      colors: [Color(0x99000000), Color(0x00000000)],
                    ),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(12, 16, 4, 4),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        DefaultTextStyle(
                          style: _light.copyWith(fontSize: 12),
                          child: Row(
                            children: [
                              if (post.city != null) ...[
                                const Icon(Icons.place_outlined, size: 14, color: Colors.white),
                                Flexible(child: Text(post.city!, overflow: TextOverflow.ellipsis)),
                                const Text(' · '),
                              ],
                              Flexible(child: RelativeTimeText(post.createdAt, clock: clock)),
                            ],
                          ),
                        ),
                        Row(
                          children: [
                            IconButton(
                              key: const Key('post-heart'),
                              tooltip: post.viewerHasHearted ? 'Batal hati' : 'Beri hati',
                              onPressed: onHeart,
                              icon: Icon(
                                post.viewerHasHearted ? Icons.favorite : Icons.favorite_border,
                                color: post.viewerHasHearted ? Colors.pinkAccent : Colors.white,
                              ),
                            ),
                            Text('${post.heartCount}', style: _light),
                            const SizedBox(width: 8),
                            const Icon(Icons.mode_comment_outlined, color: Colors.white, size: 20),
                            const SizedBox(width: 4),
                            Text('${post.replyCount}', style: _light),
                            const Spacer(),
                            if (post.canWhisper && onWhisper != null)
                              TextButton.icon(
                                key: const Key('post-whisper'),
                                onPressed: onWhisper,
                                icon: const Icon(Icons.lock_outline, color: Colors.white, size: 18),
                                label: Text('Bisik', style: _light),
                              )
                            else
                              const SizedBox(width: 12),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
