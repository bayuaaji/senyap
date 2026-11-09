import 'package:flutter/material.dart';

import '../../app/ui/ui.dart';

/// Penampil gambar layar penuh: latar hitam, pinch zoom, ketuk dua kali untuk zoom, geser ke atas/bawah
/// atau tombol tutup untuk keluar. Dengan [sendLabel] menjadi pratinjau sebelum kirim: pop `true` bila
/// tombol kirim ditekan.
class ImageViewer extends StatefulWidget {
  const ImageViewer({super.key, required this.image, this.heroTag, this.sendLabel});

  final ImageProvider image;
  final Object? heroTag;
  final String? sendLabel;

  /// Buka di atas layar sekarang dengan transisi pudar (Hero tetap jalan).
  static Future<bool?> open(BuildContext context, {required ImageProvider image, Object? heroTag, String? sendLabel}) =>
      Navigator.of(context).push<bool>(PageRouteBuilder<bool>(
        opaque: false,
        barrierColor: Colors.transparent,
        transitionDuration: const Duration(milliseconds: 220),
        reverseTransitionDuration: const Duration(milliseconds: 200),
        pageBuilder: (_, _, _) => ImageViewer(image: image, heroTag: heroTag, sendLabel: sendLabel),
        transitionsBuilder: (_, animation, _, child) => FadeTransition(opacity: animation, child: child),
      ));

  @override
  State<ImageViewer> createState() => _ImageViewerState();
}

class _ImageViewerState extends State<ImageViewer> with SingleTickerProviderStateMixin {
  static const _zoomScale = 2.5;
  static const _dismissDistance = 120.0;

  final _transform = TransformationController();
  late final AnimationController _anim =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 200));
  Animation<Matrix4>? _zoomAnimation;
  TapDownDetails? _doubleTap;
  double _dragY = 0;
  bool _zoomed = false;

  @override
  void initState() {
    super.initState();
    _anim.addListener(() {
      final a = _zoomAnimation;
      if (a != null) _transform.value = a.value;
    });
    _transform.addListener(() {
      final zoomed = _transform.value.getMaxScaleOnAxis() > 1.01;
      if (zoomed != _zoomed) setState(() => _zoomed = zoomed);
    });
  }

  @override
  void dispose() {
    _anim.dispose();
    _transform.dispose();
    super.dispose();
  }

  void _onDoubleTap() {
    final Matrix4 target;
    if (_zoomed) {
      target = Matrix4.identity();
    } else {
      final p = _doubleTap?.localPosition ?? Offset.zero;
      target = Matrix4.identity()
        ..translateByDouble(-p.dx * (_zoomScale - 1), -p.dy * (_zoomScale - 1), 0, 1)
        ..scaleByDouble(_zoomScale, _zoomScale, 1, 1);
    }
    _zoomAnimation = Matrix4Tween(begin: _transform.value, end: target)
        .animate(CurvedAnimation(parent: _anim, curve: Curves.easeOut));
    _anim.forward(from: 0);
  }

  void _close([bool? result]) => Navigator.of(context).pop(result);

  @override
  Widget build(BuildContext context) {
    final fade = (1 - (_dragY.abs() / 400)).clamp(0.3, 1.0);
    Widget image = Image(
      image: widget.image,
      fit: BoxFit.contain,
      errorBuilder: (_, _, _) => const Icon(Icons.broken_image_outlined, color: Colors.white54, size: 64),
    );
    if (widget.heroTag != null) image = Hero(tag: widget.heroTag!, child: image);

    return Scaffold(
      key: const Key('image-viewer'),
      backgroundColor: Colors.black.withValues(alpha: fade),
      body: Stack(children: [
        Positioned.fill(
          child: GestureDetector(
            onDoubleTapDown: (d) => _doubleTap = d,
            onDoubleTap: _onDoubleTap,
            // Geser untuk tutup hanya saat tidak di-zoom (saat zoom, geser = pan gambar).
            onVerticalDragUpdate: _zoomed ? null : (d) => setState(() => _dragY += d.delta.dy),
            onVerticalDragEnd: _zoomed
                ? null
                : (d) {
                    if (_dragY.abs() > _dismissDistance || (d.primaryVelocity ?? 0).abs() > 900) {
                      _close();
                    } else {
                      setState(() => _dragY = 0);
                    }
                  },
            child: Transform.translate(
              offset: Offset(0, _dragY),
              child: InteractiveViewer(
                key: const Key('image-viewer-zoom'),
                transformationController: _transform,
                panEnabled: _zoomed,
                minScale: 1,
                maxScale: 5,
                child: Center(child: image),
              ),
            ),
          ),
        ),
        SafeArea(
          child: Align(
            alignment: Alignment.topLeft,
            child: Padding(
              padding: const EdgeInsets.all(Space.md),
              child: RoundIconButton(
                key: const Key('image-viewer-close'),
                tooltip: 'Tutup',
                onImage: true,
                icon: Icons.close_rounded,
                onPressed: () => _close(false),
              ),
            ),
          ),
        ),
        if (widget.sendLabel != null)
          SafeArea(
            child: Align(
              alignment: Alignment.bottomRight,
              child: Padding(
                padding: const EdgeInsets.all(Space.lg),
                child: PrimaryButton(
                  key: const Key('image-preview-send'),
                  expand: false,
                  tone: ButtonTone.accent,
                  icon: Icons.send_rounded,
                  onPressed: () => _close(true),
                  label: widget.sendLabel!,
                ),
              ),
            ),
          ),
      ]),
    );
  }
}
