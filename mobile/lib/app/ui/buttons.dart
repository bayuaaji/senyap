import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme/theme.dart';

/// Mengecil sedikit saat ditekan (umpan balik bahwa ketukan masuk) + haptik ringan opsional.
/// Skala dimatikan bila sistem meminta animasi dikurangi.
class Pressable extends StatefulWidget {
  const Pressable({super.key, required this.child, this.onTap, this.onLongPress, this.scale = 0.98, this.haptic = false});

  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final double scale;
  final bool haptic;

  @override
  State<Pressable> createState() => _PressableState();
}

class _PressableState extends State<Pressable> {
  bool _down = false;

  void _set(bool v) {
    if (_down != v) setState(() => _down = v);
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onTap != null || widget.onLongPress != null;
    final reduce = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    return MouseRegion(
      cursor: enabled ? SystemMouseCursors.click : MouseCursor.defer,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: enabled ? (_) => _set(true) : null,
        onTapUp: enabled ? (_) => _set(false) : null,
        onTapCancel: () => _set(false),
        onTap: widget.onTap == null
            ? null
            : () {
                if (widget.haptic) HapticFeedback.lightImpact();
                widget.onTap!();
              },
        onLongPress: widget.onLongPress,
        child: AnimatedScale(
          scale: _down && !reduce ? widget.scale : 1,
          duration: Motion.fast,
          curve: Motion.curve,
          child: AnimatedOpacity(
            opacity: _down ? 0.85 : 1,
            duration: Motion.fast,
            child: widget.child,
          ),
        ),
      ),
    );
  }
}

/// Nada tombol utama: [ink] (tinta, bawaan), [accent] (jambu, hanya untuk Bisik), [danger].
enum ButtonTone { ink, accent, danger }

/// Tombol utama selebar wadah (tinggi 52, radius 12), dengan status memuat dan haptik.
class PrimaryButton extends StatelessWidget {
  const PrimaryButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.busy = false,
    this.icon,
    this.tone = ButtonTone.ink,
    this.expand = true,
  });

  final String label;
  final VoidCallback? onPressed;
  final bool busy;
  final IconData? icon;
  final ButtonTone tone;
  final bool expand;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final (bg, fg) = switch (tone) {
      ButtonTone.ink => (c.text, c.bg),
      ButtonTone.accent => (c.accent, c.onAccent),
      ButtonTone.danger => (c.danger, c.bg),
    };
    final child = AnimatedSwitcher(
      duration: Motion.fast,
      child: busy
          ? SizedBox.square(
              key: const ValueKey('busy'),
              dimension: 20,
              // Saat memuat tombol nonaktif (latar `raised`), jadi spinner memakai warna teks sekunder.
              child: CircularProgressIndicator(strokeWidth: 2, color: c.textSecondary),
            )
          : Row(key: const ValueKey('label'), mainAxisSize: MainAxisSize.min, children: [
              if (icon != null) ...[Icon(icon, size: 19), const SizedBox(width: Space.xs)],
              Flexible(child: Text(label, overflow: TextOverflow.ellipsis)),
            ]),
    );
    return FilledButton(
      style: ButtonStyle(
        minimumSize: WidgetStatePropertyAll(Size(expand ? double.infinity : 64, 52)),
        backgroundColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.disabled) ? c.raised : bg),
        foregroundColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.disabled) ? c.textTertiary : fg),
        overlayColor: WidgetStatePropertyAll(fg.withValues(alpha: 0.10)),
      ),
      onPressed: busy || onPressed == null
          ? null
          : () {
              HapticFeedback.lightImpact();
              onPressed!();
            },
      child: child,
    );
  }
}

/// Tombol sekunder: blok `raised` tanpa garis.
class SecondaryButton extends StatelessWidget {
  const SecondaryButton({super.key, required this.label, required this.onPressed, this.icon, this.expand = true});

  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final bool expand;

  @override
  Widget build(BuildContext context) {
    final style = ButtonStyle(minimumSize: WidgetStatePropertyAll(Size(expand ? double.infinity : 64, 52)));
    if (icon == null) {
      return OutlinedButton(style: style, onPressed: onPressed, child: Text(label));
    }
    return OutlinedButton.icon(
      style: style,
      onPressed: onPressed,
      icon: Icon(icon, size: 19),
      label: Text(label),
    );
  }
}

/// Tombol ikon 44×44 tanpa wadah (kembali, ⋯, tutup). [onImage] untuk di atas foto/gelap.
/// [filled] memberi latar (dipakai tombol kirim).
class RoundIconButton extends StatelessWidget {
  const RoundIconButton({
    super.key,
    required this.icon,
    required this.onPressed,
    this.tooltip,
    this.size = 44,
    this.onImage = false,
    this.color,
    this.iconColor,
    this.iconSize,
  });

  final IconData icon;
  final VoidCallback? onPressed;
  final String? tooltip;
  final double size;
  final bool onImage;

  /// Latar tombol; `null` = tanpa latar.
  final Color? color;
  final Color? iconColor;
  final double? iconSize;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final button = Pressable(
      onTap: onPressed,
      scale: 0.92,
      child: AnimatedContainer(
        duration: Motion.fast,
        width: size,
        height: size,
        decoration: BoxDecoration(
          color: color ?? (onImage ? const Color(0x59000000) : Colors.transparent),
          borderRadius: Radii.all(Radii.md),
        ),
        child: Icon(icon, size: iconSize ?? 22, color: iconColor ?? (onImage ? OnImage.text : c.text)),
      ),
    );
    return Semantics(
      button: true,
      enabled: onPressed != null,
      label: tooltip,
      child: tooltip == null ? button : Tooltip(message: tooltip!, child: button),
    );
  }
}

/// Tombol kembali, memakai `Navigator.maybePop`.
class BackCircle extends StatelessWidget {
  const BackCircle({super.key, this.onPressed});

  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => RoundIconButton(
        key: const Key('back'),
        icon: Icons.arrow_back_ios_new_rounded,
        iconSize: 20,
        tooltip: 'Kembali',
        onPressed: onPressed ?? () => Navigator.of(context).maybePop(),
      );
}
