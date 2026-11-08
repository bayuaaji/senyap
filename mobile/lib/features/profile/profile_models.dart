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

class Profile {
  const Profile({
    required this.publicId,
    required this.alias,
    required this.onboarded,
    this.aliasChangeableAt,
  });

  final String publicId;
  final Alias alias;
  final bool onboarded;

  /// `null` = alias boleh diganti sekarang.
  final DateTime? aliasChangeableAt;

  factory Profile.fromJson(Map<String, dynamic> json) => Profile(
        publicId: json['public_id'] as String,
        alias: Alias.fromJson(json['alias'] as Map<String, dynamic>),
        onboarded: json['onboarded'] as bool? ?? true,
        aliasChangeableAt: json['alias_changeable_at'] == null
            ? null
            : DateTime.parse(json['alias_changeable_at'] as String),
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

class AliasColorOption {
  const AliasColorOption({required this.name, required this.hex});

  final String name;
  final String hex;
}

class AliasCatalog {
  const AliasCatalog({required this.animals, required this.colors});

  final List<String> animals;
  final List<AliasColorOption> colors;

  factory AliasCatalog.fromJson(Map<String, dynamic> json) => AliasCatalog(
        animals: (json['animals'] as List).cast<String>(),
        colors: [
          for (final c in json['colors'] as List)
            AliasColorOption(name: c['name'] as String, hex: c['hex'] as String),
        ],
      );
}
