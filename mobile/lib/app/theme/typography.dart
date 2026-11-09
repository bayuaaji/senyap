import 'package:flutter/material.dart';

/// Dua suara: serif (Fraunces) untuk suara manusia (cerita, balasan, kutipan, judul layar), sans
/// (Plus Jakarta Sans) untuk suara app (UI, metadata, chat). Keduanya dibundel di `assets/fonts`.
abstract final class Fonts {
  static const sans = 'PlusJakartaSans';
  static const serif = 'Fraunces';
}

/// Skala tipe. `display*`/`headline*` serif; sisanya sans. Warna diisi dari tema.
TextTheme senyapTextTheme(Color text, Color secondary) {
  TextStyle s(double size, FontWeight w, double height, double spacing, [Color? c]) => TextStyle(
        fontFamily: Fonts.sans,
        fontSize: size,
        fontWeight: w,
        height: height,
        letterSpacing: spacing,
        color: c ?? text,
      );
  TextStyle serif(double size, double height, double spacing) => TextStyle(
        fontFamily: Fonts.serif,
        fontSize: size,
        fontWeight: FontWeight.w500,
        height: height,
        letterSpacing: spacing,
        color: text,
      );
  return TextTheme(
    displayLarge: serif(44, 1.05, -1.4),
    displayMedium: serif(36, 1.08, -1),
    displaySmall: serif(32, 1.1, -0.6),
    headlineLarge: serif(32, 1.1, -0.6),
    headlineMedium: serif(26, 1.15, -0.4),
    headlineSmall: serif(22, 1.2, -0.3),
    titleLarge: s(18, FontWeight.w600, 1.3, -0.2),
    titleMedium: s(16, FontWeight.w600, 1.3, -0.1),
    titleSmall: s(15, FontWeight.w600, 1.3, -0.05),
    bodyLarge: s(16, FontWeight.w400, 1.45, -0.1),
    bodyMedium: s(14.5, FontWeight.w400, 1.5, 0, secondary),
    bodySmall: s(13, FontWeight.w500, 1.4, 0, secondary),
    labelLarge: s(15.5, FontWeight.w600, 1.2, -0.1),
    labelMedium: s(13.5, FontWeight.w600, 1.2, 0),
    labelSmall: s(12, FontWeight.w600, 1.2, 0.1, secondary),
  );
}

/// Teks post di atas gambar: serif, ukuran menyesuaikan panjang (minimal 21).
abstract final class PostType {
  static double sizeFor(int length) => switch (length) {
        <= 60 => 30,
        <= 110 => 26,
        <= 170 => 23,
        _ => 21,
      };

  static TextStyle body(int length, {Color color = Colors.white}) {
    final size = sizeFor(length);
    return TextStyle(
      fontFamily: Fonts.serif,
      fontSize: size,
      height: size >= 26 ? 1.2 : 1.3,
      letterSpacing: size >= 26 ? -0.5 : -0.2,
      fontWeight: FontWeight.w500,
      color: color,
      shadows: const [Shadow(blurRadius: 20, color: Color(0x80000000)), Shadow(blurRadius: 3, color: Color(0x40000000))],
    );
  }

  /// Kutipan (potongan cerita di layar Bisik dan kepala chat).
  static const quote = TextStyle(
    fontFamily: Fonts.serif,
    fontSize: 17,
    height: 1.45,
    fontStyle: FontStyle.italic,
    fontWeight: FontWeight.w400,
    letterSpacing: -0.1,
  );

  /// Isi balasan: serif tegak, nyaman dibaca berparagraf.
  static const reply = TextStyle(
    fontFamily: Fonts.serif,
    fontSize: 16.5,
    height: 1.5,
    fontWeight: FontWeight.w400,
    letterSpacing: -0.1,
  );
}
