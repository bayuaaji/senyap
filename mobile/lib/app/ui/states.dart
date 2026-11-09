import 'dart:io' show Platform;

import 'package:flutter/material.dart';

import '../theme/theme.dart';

/// Di widget test animasi berulang dimatikan supaya `pumpAndSettle` selesai.
final bool _inTest = Platform.environment.containsKey('FLUTTER_TEST');

/// Empty / error state: ikon kecil polos (opsional), judul serif, satu kalimat sebab, satu aksi.
/// Rata kiri mengikuti judul layar; tanpa ilustrasi atau pendar.
class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    this.icon,
    required this.title,
    this.message,
    this.action,
    this.tint,
    this.compact = false,
  });

  final IconData? icon;
  final String title;
  final String? message;
  final Widget? action;

  /// Warna ikon; bawaan tertiary.
  final Color? tint;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Padding(
      padding: EdgeInsets.symmetric(horizontal: Space.gutter, vertical: compact ? Space.md : Space.xl),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (icon != null) ...[
          Icon(icon, size: 28, color: tint ?? c.textTertiary),
          const SizedBox(height: Space.md),
        ],
        Text(title, style: context.text.headlineSmall),
        if (message != null) ...[
          const SizedBox(height: Space.xs),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: Text(message!, style: context.text.bodyMedium),
          ),
        ],
        if (action != null) ...[const SizedBox(height: Space.xl), action!],
      ]),
    );
  }
}

/// Kilau berjalan untuk skeleton.
class Shimmer extends StatefulWidget {
  const Shimmer({super.key, required this.child});

  final Widget child;

  @override
  State<Shimmer> createState() => _ShimmerState();
}

class _ShimmerState extends State<Shimmer> with SingleTickerProviderStateMixin {
  late final _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1400));

  @override
  void initState() {
    super.initState();
    if (!_inTest) _c.repeat();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Hormati "kurangi gerakan": skeleton tetap tampil, kilaunya berhenti.
    if (MediaQuery.maybeDisableAnimationsOf(context) == true) _c.stop();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return AnimatedBuilder(
      animation: _c,
      builder: (context, child) => ShaderMask(
        blendMode: BlendMode.srcATop,
        shaderCallback: (rect) {
          final dx = rect.width * (_c.value * 2.4 - 1.2);
          return LinearGradient(
            colors: [c.raised, c.overlay, c.raised],
            stops: const [0.35, 0.5, 0.65],
            transform: _Slide(dx),
          ).createShader(rect);
        },
        child: child,
      ),
      child: widget.child,
    );
  }
}

class _Slide extends GradientTransform {
  const _Slide(this.dx);

  final double dx;

  @override
  Matrix4? transform(Rect bounds, {TextDirection? textDirection}) => Matrix4.translationValues(dx, 0, 0);
}

/// Kotak abu skeleton.
class SkeletonBox extends StatelessWidget {
  const SkeletonBox({super.key, this.width, this.height = 14, this.radius = 8, this.circle = false});

  final double? width;
  final double height;
  final double radius;
  final bool circle;

  @override
  Widget build(BuildContext context) => Container(
        width: circle ? height : width,
        height: height,
        decoration: BoxDecoration(
          color: context.colors.raised,
          shape: circle ? BoxShape.circle : BoxShape.rectangle,
          borderRadius: circle ? null : Radii.all(radius),
        ),
      );
}

/// Skeleton baris daftar (avatar + dua garis), dipakai di inbox dan daftar lain.
class ListSkeleton extends StatelessWidget {
  const ListSkeleton({super.key, this.count = 6});

  final int count;

  @override
  Widget build(BuildContext context) => Shimmer(
        child: ListView.builder(
          physics: const NeverScrollableScrollPhysics(),
          padding: const EdgeInsets.symmetric(vertical: Space.xs),
          itemCount: count,
          itemBuilder: (context, i) => Padding(
            padding: const EdgeInsets.symmetric(horizontal: Space.gutter, vertical: Space.sm),
            child: Row(children: [
              const SkeletonBox(height: 52, circle: true),
              const SizedBox(width: Space.md),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  SkeletonBox(width: 120.0 + (i % 3) * 30, height: 13),
                  const SizedBox(height: Space.xs),
                  SkeletonBox(width: 180.0 + (i % 2) * 40, height: 11),
                ]),
              ),
            ]),
          ),
        ),
      );
}

/// Pemuat kecil di tengah (ganti CircularProgressIndicator default).
class Loader extends StatelessWidget {
  const Loader({super.key, this.size = 22, this.color});

  final double size;
  final Color? color;

  @override
  Widget build(BuildContext context) => Center(
        child: SizedBox.square(
          dimension: size,
          child: CircularProgressIndicator(strokeWidth: 2, color: color ?? context.colors.textSecondary),
        ),
      );
}
