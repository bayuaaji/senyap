import 'package:flutter/material.dart';

import '../../app/theme/theme.dart';
import 'profile_models.dart';

Color aliasColor(String hex) => Color(int.parse(hex.substring(1), radix: 16) | 0xFF000000);

/// Lingkaran warna alias (polos, tanpa gradien/cincin/pendar) berisi emoji avatar; tanpa avatar, huruf
/// pertama nama. Abu-abu bila [colorHex] kosong (akun dihapus).
class IdentityAvatar extends StatelessWidget {
  const IdentityAvatar({super.key, required this.name, this.colorHex, this.emoji, this.radius = 28});

  final String name;
  final String? colorHex;
  final String? emoji;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final base = colorHex == null ? c.overlay : aliasColor(colorHex!);
    final fg = base.computeLuminance() > 0.4 ? const Color(0xFF191C1A) : Colors.white;
    final e = emoji;
    return Container(
      width: radius * 2,
      height: radius * 2,
      alignment: Alignment.center,
      decoration: BoxDecoration(shape: BoxShape.circle, color: base),
      child: e != null && e.isNotEmpty
          ? Text(e, key: const Key('avatar-emoji'), style: TextStyle(fontSize: radius * 1.0, height: 1.1))
          : Text(
              name.isEmpty ? '?' : name.characters.first,
              style: TextStyle(
                color: fg,
                fontSize: radius * 0.86,
                height: 1.1,
                fontWeight: FontWeight.w500,
                fontFamily: Fonts.serif,
              ),
            ),
    );
  }
}

/// Avatar dari [Alias] (+ emoji avatar profil bila ada).
class AliasAvatar extends StatelessWidget {
  const AliasAvatar({super.key, required this.alias, this.emoji, this.radius = 28});

  final Alias alias;
  final String? emoji;
  final double radius;

  @override
  Widget build(BuildContext context) =>
      IdentityAvatar(name: alias.name, colorHex: alias.color, emoji: emoji, radius: radius);
}

/// Ikon gender kecil (♂/♀) di samping alias. Tidak tampil untuk [Gender.unspecified].
class GenderMark extends StatelessWidget {
  const GenderMark(this.gender, {super.key, this.size = 14, this.color});

  final Gender gender;
  final double size;

  /// Bawaan: warna teks sekunder (gender dibaca dari bentuk ikon, bukan warna).
  final Color? color;

  @override
  Widget build(BuildContext context) {
    return switch (gender) {
      Gender.unspecified => const SizedBox.shrink(),
      Gender.male => Icon(Icons.male_rounded, key: const Key('gender-male'), size: size,
          color: color ?? context.colors.textSecondary, semanticLabel: 'laki-laki'),
      Gender.female => Icon(Icons.female_rounded, key: const Key('gender-female'), size: size,
          color: color ?? context.colors.textSecondary, semanticLabel: 'perempuan'),
    };
  }
}

/// Nama alias + ikon gender dalam satu baris yang bisa terpotong.
class AliasName extends StatelessWidget {
  const AliasName({super.key, required this.text, required this.gender, this.style, this.genderColor});

  final String text;
  final Gender gender;
  final TextStyle? style;
  final Color? genderColor;

  @override
  Widget build(BuildContext context) {
    final size = (style?.fontSize ?? DefaultTextStyle.of(context).style.fontSize ?? 14) + 1;
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Flexible(child: Text(text, style: style, maxLines: 1, overflow: TextOverflow.ellipsis)),
      if (gender != Gender.unspecified) ...[
        const SizedBox(width: 4),
        GenderMark(gender, size: size, color: genderColor),
      ],
    ]);
  }
}
