import 'package:flutter/material.dart';

/// Token desain Senyap: "surat tanpa nama". Lihat `mobile/DESIGN.md`.
///
/// Semua layar memakai token di sini (lewat `context.colors`, [Space], [Radii], [Motion]), bukan literal.

/// Palet. Mengikuti tema sistem: gelap "malam" (tinta kehijauan), terang "kertas".
/// Satu aksen (jambu) untuk Bisik dan pilihan aktif; tombol utama lain memakai tinta ([text]).
@immutable
class SenyapColors extends ThemeExtension<SenyapColors> {
  const SenyapColors({
    required this.bg,
    required this.surface,
    required this.raised,
    required this.overlay,
    required this.line,
    required this.text,
    required this.textSecondary,
    required this.textTertiary,
    required this.accent,
    required this.onAccent,
    required this.onAccentMuted,
    required this.accentSoft,
    required this.danger,
    required this.dangerSoft,
    required this.success,
  });

  /// Latar layar.
  final Color bg;

  /// Sheet, dialog, panel.
  final Color surface;

  /// Input, gelembung lawan bicara, tombol sekunder.
  final Color raised;

  /// Pressed, skeleton.
  final Color overlay;

  /// Garis rambut.
  final Color line;

  final Color text;
  final Color textSecondary;
  final Color textTertiary;

  /// Jambu: Bisik, gelembung sendiri, hati aktif, tab aktif, belum dibaca, tautan.
  final Color accent;
  final Color onAccent;

  /// Jam/status di atas gelembung aksen (tetap lolos AA).
  final Color onAccentMuted;
  final Color accentSoft;

  final Color danger;
  final Color dangerSoft;
  final Color success;

  static const dark = SenyapColors(
    bg: Color(0xFF121413),
    surface: Color(0xFF1A1D1B),
    raised: Color(0xFF232724),
    overlay: Color(0xFF2D322E),
    line: Color(0xFF2F3431),
    text: Color(0xFFECE8DF),
    textSecondary: Color(0xFFB3B5AD),
    textTertiary: Color(0xFF8C9089),
    accent: Color(0xFFF2A08F),
    onAccent: Color(0xFF2A120C),
    onAccentMuted: Color(0xFF5C362D),
    accentSoft: Color(0x24F2A08F),
    danger: Color(0xFFFF6B63),
    dangerSoft: Color(0x1FFF6B63),
    success: Color(0xFF8FCBA6),
  );

  static const light = SenyapColors(
    bg: Color(0xFFF1EFE8),
    surface: Color(0xFFFAF9F5),
    raised: Color(0xFFE6E4DC),
    overlay: Color(0xFFDCD9D0),
    line: Color(0xFFD9D6CC),
    text: Color(0xFF191C1A),
    textSecondary: Color(0xFF4B504B),
    textTertiary: Color(0xFF5F645E),
    accent: Color(0xFFA8384F),
    onAccent: Color(0xFFFFFFFF),
    onAccentMuted: Color(0xFFEED7DC),
    accentSoft: Color(0x1AA8384F),
    danger: Color(0xFFC42B1C),
    dangerSoft: Color(0x14C42B1C),
    success: Color(0xFF2F7A4F),
  );

  @override
  SenyapColors copyWith() => this;

  @override
  SenyapColors lerp(SenyapColors? other, double t) {
    if (other == null) return this;
    Color l(Color a, Color b) => Color.lerp(a, b, t)!;
    return SenyapColors(
      bg: l(bg, other.bg),
      surface: l(surface, other.surface),
      raised: l(raised, other.raised),
      overlay: l(overlay, other.overlay),
      line: l(line, other.line),
      text: l(text, other.text),
      textSecondary: l(textSecondary, other.textSecondary),
      textTertiary: l(textTertiary, other.textTertiary),
      accent: l(accent, other.accent),
      onAccent: l(onAccent, other.onAccent),
      onAccentMuted: l(onAccentMuted, other.onAccentMuted),
      accentSoft: l(accentSoft, other.accentSoft),
      danger: l(danger, other.danger),
      dangerSoft: l(dangerSoft, other.dangerSoft),
      success: l(success, other.success),
    );
  }
}

/// Skala spasi kelipatan 4. Gutter layar 24.
abstract final class Space {
  static const xxs = 4.0;
  static const xs = 8.0;
  static const sm = 12.0;
  static const md = 16.0;
  static const lg = 20.0;
  static const xl = 24.0;
  static const xxl = 32.0;
  static const x3 = 40.0;
  static const x4 = 56.0;
  static const x5 = 72.0;

  /// Padding horizontal layar.
  static const gutter = 24.0;
  static const screen = EdgeInsets.symmetric(horizontal: gutter);

  /// Jarak antarbagian di daftar dan formulir.
  static const section = 32.0;

  /// Tinggi minimum baris yang bisa diketuk.
  static const row = 56.0;
}

/// Empat radius. Lingkaran hanya untuk avatar; tidak ada pil.
abstract final class Radii {
  /// Lencana, thumbnail kecil.
  static const sm = 8.0;

  /// Input, tombol, chip.
  static const md = 12.0;

  /// Foto cerita, gelembung, panel.
  static const lg = 18.0;

  /// Sheet, dialog.
  static const xl = 24.0;

  static BorderRadius all(double r) => BorderRadius.circular(r);
}

abstract final class Motion {
  static const fast = Duration(milliseconds: 180);
  static const base = Duration(milliseconds: 240);
  static const slow = Duration(milliseconds: 320);
  static const curve = Curves.easeOutCubic;

  /// Durasi yang menghormati "kurangi gerakan" di sistem.
  static Duration of(BuildContext context, Duration d) =>
      MediaQuery.maybeDisableAnimationsOf(context) == true ? Duration.zero : d;
}

/// Teks dan ikon di atas foto latar post (selalu terang, apa pun temanya).
abstract final class OnImage {
  static const text = Color(0xFFFFFFFF);
  static const soft = Color(0xE6FFFFFF);
  static const faint = Color(0xB3FFFFFF);

  /// Peredup merata di atas foto (lihat DESIGN.md, bagian kontras).
  static const scrim = Color(0x80000000);
  static const shadow = [Shadow(blurRadius: 16, color: Color(0x80000000)), Shadow(blurRadius: 2, color: Color(0x4D000000))];
}

extension SenyapThemeX on BuildContext {
  SenyapColors get colors => Theme.of(this).extension<SenyapColors>() ?? SenyapColors.dark;
  TextTheme get text => Theme.of(this).textTheme;
}
