/// Gender yang ditampilkan di samping alias (post, balasan, Bisik). Wire: `male|female|unspecified`.
enum Gender {
  male('male', 'Laki-laki'),
  female('female', 'Perempuan'),
  unspecified('unspecified', 'Tidak disebut');

  const Gender(this.wire, this.label);

  final String wire;
  final String label;

  static Gender parse(Object? value) => switch (value) {
        'male' => Gender.male,
        'female' => Gender.female,
        _ => Gender.unspecified,
      };
}

class Alias {
  const Alias({required this.name, required this.tag, required this.color});

  final String name;
  final String tag;

  /// Hex `#RRGGBB`, warna avatar.
  final String color;

  factory Alias.fromJson(Map<String, dynamic> json) => Alias(
        name: json['name'] as String,
        tag: json['tag'] as String,
        color: json['color'] as String,
      );

  String get display => '$name #$tag';
}

/// Avatar ilustrasi (emoji) yang dipilih dari katalog. Bukan foto asli.
class AvatarOption {
  const AvatarOption({required this.id, required this.emoji});

  final String id;
  final String emoji;

  factory AvatarOption.fromJson(Map<String, dynamic> json) =>
      AvatarOption(id: json['id'] as String, emoji: json['emoji'] as String);
}

class Profile {
  const Profile({
    required this.publicId,
    required this.alias,
    required this.onboarded,
    this.aliasChangeableAt,
    this.gender = Gender.unspecified,
    this.avatar,
  });

  final String publicId;
  final Alias alias;
  final bool onboarded;

  /// `null` = alias boleh diganti sekarang.
  final DateTime? aliasChangeableAt;

  final Gender gender;

  /// `null` = belum memilih avatar (tampil inisial alias).
  final AvatarOption? avatar;

  factory Profile.fromJson(Map<String, dynamic> json) => Profile(
        publicId: json['public_id'] as String,
        alias: Alias.fromJson(json['alias'] as Map<String, dynamic>),
        onboarded: json['onboarded'] as bool? ?? true,
        aliasChangeableAt: json['alias_changeable_at'] == null
            ? null
            : DateTime.parse(json['alias_changeable_at'] as String),
        gender: Gender.parse(json['gender']),
        avatar: json['avatar'] is Map<String, dynamic>
            ? AvatarOption.fromJson(json['avatar'] as Map<String, dynamic>)
            : null,
      );
}

class AliasSuggestion {
  const AliasSuggestion({required this.alias, required this.token});

  final Alias alias;
  final String token;

  factory AliasSuggestion.fromJson(Map<String, dynamic> json) => AliasSuggestion(
        alias: Alias.fromJson(json),
        token: json['suggestion_token'] as String,
      );
}

/// Kata kedua alias beserta warnanya.
class AliasColorOption {
  const AliasColorOption({required this.name, required this.hex});

  final String name;
  final String hex;
}

/// Katalog alias per gender: kata pertama, kata kedua (+ warna), dan avatar yang bisa dipilih.
class AliasCatalog {
  const AliasCatalog({
    this.gender = Gender.unspecified,
    required this.first,
    required this.second,
    this.avatars = const [],
  });

  final Gender gender;
  final List<String> first;
  final List<AliasColorOption> second;
  final List<AvatarOption> avatars;

  factory AliasCatalog.fromJson(Map<String, dynamic> json) => AliasCatalog(
        gender: Gender.parse(json['gender']),
        first: (json['first'] as List).cast<String>(),
        second: [
          for (final c in json['second'] as List)
            AliasColorOption(name: c['name'] as String, hex: c['hex'] as String),
        ],
        avatars: [
          for (final a in (json['avatars'] as List?) ?? const [])
            AvatarOption.fromJson(a as Map<String, dynamic>),
        ],
      );
}
