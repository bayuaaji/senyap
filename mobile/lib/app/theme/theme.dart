import 'package:flutter/cupertino.dart' show CupertinoPageTransitionsBuilder;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'tokens.dart';
import 'typography.dart';

export 'tokens.dart';
export 'typography.dart';

/// `ThemeData` lengkap Senyap dari token (lihat `mobile/DESIGN.md`).
ThemeData buildSenyapTheme(Brightness brightness) {
  final c = brightness == Brightness.dark ? SenyapColors.dark : SenyapColors.light;
  final dark = brightness == Brightness.dark;
  final text = senyapTextTheme(c.text, c.textSecondary);

  final scheme = ColorScheme(
    brightness: brightness,
    // Tombol utama memakai tinta (warna teks); aksen jambu dipakai lewat token `accent` secara eksplisit.
    primary: c.text,
    onPrimary: c.bg,
    primaryContainer: c.raised,
    onPrimaryContainer: c.text,
    secondary: c.accent,
    onSecondary: c.onAccent,
    secondaryContainer: c.accentSoft,
    onSecondaryContainer: c.accent,
    tertiary: c.accent,
    onTertiary: c.onAccent,
    error: c.danger,
    onError: c.bg,
    errorContainer: c.dangerSoft,
    onErrorContainer: c.danger,
    surface: c.surface,
    onSurface: c.text,
    onSurfaceVariant: c.textSecondary,
    surfaceDim: c.bg,
    surfaceBright: c.overlay,
    surfaceContainerLowest: c.bg,
    surfaceContainerLow: c.surface,
    surfaceContainer: c.surface,
    surfaceContainerHigh: c.raised,
    surfaceContainerHighest: c.raised,
    outline: c.textTertiary,
    outlineVariant: c.line,
    shadow: Colors.black,
    scrim: Colors.black,
    inverseSurface: c.text,
    onInverseSurface: c.bg,
    inversePrimary: c.accent,
  );

  final shape = WidgetStatePropertyAll<OutlinedBorder>(RoundedRectangleBorder(borderRadius: Radii.all(Radii.md)));
  final labelStyle = WidgetStatePropertyAll(text.labelLarge);
  // Gaya isi: label mengambang tetap di dalam kolom; fokus ditandai garis bawah tinta di dalam sudut membulat.
  final inputBorder = UnderlineInputBorder(borderRadius: Radii.all(Radii.md), borderSide: BorderSide.none);
  Color pressed(Color on) => on.withValues(alpha: 0.10);

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    fontFamily: Fonts.sans,
    textTheme: text,
    scaffoldBackgroundColor: c.bg,
    canvasColor: c.bg,
    splashFactory: NoSplash.splashFactory,
    highlightColor: c.text.withValues(alpha: 0.05),
    splashColor: Colors.transparent,
    hoverColor: c.text.withValues(alpha: 0.04),
    focusColor: c.text.withValues(alpha: 0.10),
    dividerColor: c.line,
    extensions: [c],
    visualDensity: VisualDensity.standard,
    materialTapTargetSize: MaterialTapTargetSize.padded,
    pageTransitionsTheme: const PageTransitionsTheme(builders: {
      TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
      TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
    }),
    appBarTheme: AppBarTheme(
      backgroundColor: c.bg,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleSpacing: Space.gutter,
      foregroundColor: c.text,
      titleTextStyle: text.titleMedium,
      systemOverlayStyle: dark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
    ),
    cardTheme: const CardThemeData(
      color: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: EdgeInsets.zero,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: ButtonStyle(
        shape: shape,
        textStyle: labelStyle,
        minimumSize: const WidgetStatePropertyAll(Size(64, 48)),
        padding: const WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal: Space.lg)),
        elevation: const WidgetStatePropertyAll(0),
        backgroundColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.disabled) ? c.raised : c.text),
        foregroundColor:
            WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.disabled) ? c.textTertiary : c.bg),
        overlayColor: WidgetStatePropertyAll(pressed(c.bg)),
        animationDuration: Motion.fast,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: ButtonStyle(
        shape: shape,
        textStyle: labelStyle,
        minimumSize: const WidgetStatePropertyAll(Size(64, 48)),
        padding: const WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal: Space.lg)),
        backgroundColor: WidgetStatePropertyAll(c.raised),
        foregroundColor:
            WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.disabled) ? c.textTertiary : c.text),
        side: const WidgetStatePropertyAll(BorderSide.none),
        overlayColor: WidgetStatePropertyAll(pressed(c.text)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: ButtonStyle(
        shape: shape,
        textStyle: WidgetStatePropertyAll(text.labelMedium?.copyWith(fontSize: 14.5)),
        minimumSize: const WidgetStatePropertyAll(Size(48, 44)),
        padding: const WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal: Space.sm)),
        foregroundColor:
            WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.disabled) ? c.textTertiary : c.text),
        overlayColor: WidgetStatePropertyAll(pressed(c.text)),
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: ButtonStyle(
        foregroundColor: WidgetStatePropertyAll(c.text),
        overlayColor: WidgetStatePropertyAll(pressed(c.text)),
        minimumSize: const WidgetStatePropertyAll(Size(44, 44)),
      ),
    ),
    iconTheme: IconThemeData(color: c.text, size: 22),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: c.raised,
      floatingLabelBehavior: FloatingLabelBehavior.auto,
      contentPadding: const EdgeInsets.symmetric(horizontal: Space.md, vertical: 16),
      border: inputBorder,
      enabledBorder: inputBorder,
      disabledBorder: inputBorder,
      focusedBorder: inputBorder.copyWith(borderSide: BorderSide(color: c.text, width: 2)),
      errorBorder: inputBorder.copyWith(borderSide: BorderSide(color: c.danger, width: 1.5)),
      focusedErrorBorder: inputBorder.copyWith(borderSide: BorderSide(color: c.danger, width: 2)),
      labelStyle: text.bodyLarge?.copyWith(color: c.textTertiary),
      floatingLabelStyle: text.bodySmall?.copyWith(color: c.textSecondary, fontWeight: FontWeight.w600),
      hintStyle: text.bodyLarge?.copyWith(color: c.textTertiary),
      helperStyle: text.bodySmall?.copyWith(color: c.textTertiary),
      errorStyle: text.bodySmall?.copyWith(color: c.danger),
      prefixIconColor: c.textTertiary,
      suffixIconColor: c.textTertiary,
    ),
    textSelectionTheme: TextSelectionThemeData(
      cursorColor: c.accent,
      selectionColor: c.accent.withValues(alpha: 0.3),
      selectionHandleColor: c.accent,
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: c.text,
      elevation: 0,
      insetPadding: const EdgeInsets.fromLTRB(Space.md, 0, Space.md, Space.sm),
      shape: RoundedRectangleBorder(borderRadius: Radii.all(Radii.md)),
      contentTextStyle: text.bodyMedium?.copyWith(color: c.bg, fontWeight: FontWeight.w500),
      actionTextColor: c.bg,
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: c.surface,
      surfaceTintColor: Colors.transparent,
      modalBackgroundColor: c.surface,
      modalBarrierColor: Colors.black.withValues(alpha: 0.55),
      elevation: 0,
      modalElevation: 0,
      showDragHandle: true,
      dragHandleColor: c.overlay,
      dragHandleSize: const Size(36, 4),
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(Radii.xl))),
      clipBehavior: Clip.antiAlias,
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: c.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      barrierColor: Colors.black.withValues(alpha: 0.6),
      shape: RoundedRectangleBorder(borderRadius: Radii.all(Radii.xl)),
      titleTextStyle: text.headlineSmall,
      contentTextStyle: text.bodyMedium,
      insetPadding: const EdgeInsets.symmetric(horizontal: Space.xl, vertical: Space.xl),
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: c.raised,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: Radii.all(Radii.md), side: BorderSide(color: c.line)),
      textStyle: text.bodyLarge,
      labelTextStyle: WidgetStatePropertyAll(text.bodyLarge),
      position: PopupMenuPosition.under,
    ),
    menuTheme: MenuThemeData(
      style: MenuStyle(
        backgroundColor: WidgetStatePropertyAll(c.raised),
        surfaceTintColor: const WidgetStatePropertyAll(Colors.transparent),
        shape: WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius: Radii.all(Radii.md))),
      ),
    ),
    dropdownMenuTheme: DropdownMenuThemeData(
      menuStyle: MenuStyle(
        backgroundColor: WidgetStatePropertyAll(c.raised),
        shape: WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius: Radii.all(Radii.md))),
      ),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? c.onAccent : c.textSecondary),
      trackColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? c.accent : c.overlay),
      trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
      thumbIcon: const WidgetStatePropertyAll(null),
    ),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? c.text : Colors.transparent),
      checkColor: WidgetStatePropertyAll(c.bg),
      side: BorderSide(color: c.textTertiary, width: 1.6),
      shape: RoundedRectangleBorder(borderRadius: Radii.all(5)),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: c.text,
      linearTrackColor: c.raised,
      circularTrackColor: Colors.transparent,
      refreshBackgroundColor: c.surface,
    ),
    listTileTheme: ListTileThemeData(
      iconColor: c.textSecondary,
      textColor: c.text,
      titleTextStyle: text.bodyLarge?.copyWith(fontWeight: FontWeight.w500),
      subtitleTextStyle: text.bodySmall,
      contentPadding: Space.screen,
      minVerticalPadding: Space.sm,
    ),
    dividerTheme: DividerThemeData(color: c.line, thickness: 0.6, space: 1),
    badgeTheme: BadgeThemeData(
      backgroundColor: c.accent,
      textColor: c.onAccent,
      textStyle: text.labelSmall?.copyWith(fontWeight: FontWeight.w700, letterSpacing: 0),
      padding: const EdgeInsets.symmetric(horizontal: 6),
      largeSize: 18,
    ),
    tooltipTheme: TooltipThemeData(
      decoration: BoxDecoration(color: c.text, borderRadius: Radii.all(Radii.sm)),
      textStyle: text.bodySmall?.copyWith(color: c.bg),
    ),
  );
}
