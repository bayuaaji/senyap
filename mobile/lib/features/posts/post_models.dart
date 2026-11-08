/// Model post, balasan, dan galeri latar (functional spec §4, FS-02, FS-04). Author hanya `public_id` + alias.
library;

const postCategories = <String, String>{
  'curhat': 'Curhat',
  'cinta': 'Cinta',
  'kerja': 'Kerja',
  'kampus': 'Kampus',
  'keluarga': 'Keluarga',
  'pengakuan': 'Pengakuan',
  'tanya': 'Tanya',
  'lainnya': 'Lainnya',
};

class Author {
  const Author({this.publicId, required this.name, this.tag, this.color, this.isPostAuthor = false});

  /// `null` bila akun sudah dihapus permanen ("[dihapus]").
  final String? publicId;
  final String name;
  final String? tag;
  final String? color;

  /// Lencana Penulis (hanya di balasan).
  final bool isPostAuthor;

  factory Author.fromJson(Map<String, dynamic> j) => Author(
        publicId: j['public_id'] as String?,
        name: j['name'] as String,
        tag: j['tag'] as String?,
        color: j['color'] as String?,
        isPostAuthor: j['is_post_author'] as bool? ?? false,
      );

  String get display => tag == null ? name : '$name #$tag';
}

class BackgroundImage {
  const BackgroundImage({required this.url, this.blurhash});

  final String url;
  final String? blurhash;

  factory BackgroundImage.fromJson(Map<String, dynamic> j) =>
      BackgroundImage(url: j['url'] as String, blurhash: j['blurhash'] as String?);
}

class Post {
  const Post({
    required this.id,
    required this.author,
    required this.body,
    required this.category,
    this.background,
    this.city,
    this.heartCount = 0,
    this.replyCount = 0,
    this.viewerHasHearted = false,
    this.isMine = false,
    this.canWhisper = false,
    required this.createdAt,
    this.supportCard = false,
  });

  final String id;
  final Author author;
  final String body;
  final String category;
  final BackgroundImage? background;
  final String? city;
  final int heartCount;
  final int replyCount;
  final bool viewerHasHearted;
  final bool isMine;
  final bool canWhisper;
  final DateTime createdAt;

  /// Hanya di respons membuat post: tulisan terdeteksi berisiko menyakiti diri → tampilkan kartu bantuan.
  final bool supportCard;

  factory Post.fromJson(Map<String, dynamic> j) => Post(
        id: j['id'] as String,
        author: Author.fromJson(j['author'] as Map<String, dynamic>),
        body: j['body'] as String,
        category: j['category'] as String,
        background: j['background'] == null ? null : BackgroundImage.fromJson(j['background'] as Map<String, dynamic>),
        city: j['city'] as String?,
        heartCount: j['heart_count'] as int? ?? 0,
        replyCount: j['reply_count'] as int? ?? 0,
        viewerHasHearted: j['viewer_has_hearted'] as bool? ?? false,
        isMine: j['is_mine'] as bool? ?? false,
        canWhisper: j['can_whisper'] as bool? ?? false,
        createdAt: DateTime.parse(j['created_at'] as String),
        supportCard: j['support_card'] as bool? ?? false,
      );

  Post copyWith({int? heartCount, int? replyCount, bool? viewerHasHearted}) => Post(
        id: id,
        author: author,
        body: body,
        category: category,
        background: background,
        city: city,
        heartCount: heartCount ?? this.heartCount,
        replyCount: replyCount ?? this.replyCount,
        viewerHasHearted: viewerHasHearted ?? this.viewerHasHearted,
        isMine: isMine,
        canWhisper: canWhisper,
        createdAt: createdAt,
      );

  /// Hati optimistis di client; angka dari server tetap eventual (counter terbatch).
  Post withHeart(bool hearted) => hearted == viewerHasHearted
      ? this
      : copyWith(viewerHasHearted: hearted, heartCount: hearted ? heartCount + 1 : (heartCount > 0 ? heartCount - 1 : 0));
}

class Reply {
  const Reply({
    required this.id,
    required this.postId,
    required this.author,
    required this.body,
    required this.createdAt,
    this.isMine = false,
    this.canWhisper = false,
    this.isHidden = false,
    this.supportCard = false,
  });

  final String id;
  final String postId;
  final Author author;
  final String body;
  final DateTime createdAt;
  final bool isMine;
  final bool canWhisper;
  final bool isHidden;

  /// Hanya di respons membuat balasan (lihat [Post.supportCard]).
  final bool supportCard;

  factory Reply.fromJson(Map<String, dynamic> j) => Reply(
        id: j['id'] as String,
        postId: j['post_id'] as String,
        author: Author.fromJson(j['author'] as Map<String, dynamic>),
        body: j['body'] as String,
        createdAt: DateTime.parse(j['created_at'] as String),
        isMine: j['is_mine'] as bool? ?? false,
        canWhisper: j['can_whisper'] as bool? ?? false,
        isHidden: j['is_hidden'] as bool? ?? false,
        supportCard: j['support_card'] as bool? ?? false,
      );
}

class GalleryItem {
  const GalleryItem({required this.id, required this.url, this.blurhash});

  final String id;
  final String url;
  final String? blurhash;

  factory GalleryItem.fromJson(Map<String, dynamic> j) =>
      GalleryItem(id: j['id'] as String, url: j['url'] as String, blurhash: j['blurhash'] as String?);
}

/// Halaman cursor `{items, next_cursor}`.
class PageOf<T> {
  const PageOf(this.items, this.nextCursor);

  final List<T> items;
  final String? nextCursor;

  static PageOf<T> fromJson<T>(Map<String, dynamic> j, T Function(Map<String, dynamic>) item) => PageOf(
        (j['items'] as List).map((e) => item(e as Map<String, dynamic>)).toList(),
        j['next_cursor'] as String?,
      );
}

enum FeedKind { latest, popular, nearby, category }
