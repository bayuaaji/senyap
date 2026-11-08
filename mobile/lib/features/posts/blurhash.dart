import 'dart:math' as math;
import 'dart:ui' show ImageFilter;

import 'package:flutter/widgets.dart';

const _chars = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~';

int _decode83(String s) {
  var v = 0;
  for (final c in s.split('')) {
    final d = _chars.indexOf(c);
    if (d < 0) throw const FormatException('blurhash tidak valid');
    v = v * 83 + d;
  }
  return v;
}

double _srgbToLinear(int v) {
  final x = v / 255;
  return x <= 0.04045 ? x / 12.92 : math.pow((x + 0.055) / 1.055, 2.4).toDouble();
}

int _linearToSrgb(double v) {
  final x = v.clamp(0.0, 1.0);
  return x <= 0.0031308 ? (x * 12.92 * 255 + 0.5).round() : ((1.055 * math.pow(x, 1 / 2.4) - 0.055) * 255 + 0.5).round();
}

double _signPow(double v, double e) => (v < 0 ? -1 : 1) * math.pow(v.abs(), e).toDouble();

/// Dekode BlurHash (https://blurha.sh) ke grid warna [width] × [height] (baris demi baris).
/// Melempar [FormatException] bila hash rusak.
List<Color> decodeBlurHash(String hash, int width, int height) {
  if (hash.length < 6) throw const FormatException('blurhash terlalu pendek');
  final sizeFlag = _decode83(hash[0]);
  final numY = sizeFlag ~/ 9 + 1;
  final numX = sizeFlag % 9 + 1;
  if (hash.length != 4 + 2 * numX * numY) throw const FormatException('panjang blurhash salah');
  final maxValue = (_decode83(hash[1]) + 1) / 166;

  final colors = List<List<double>>.generate(numX * numY, (i) {
    if (i == 0) {
      final v = _decode83(hash.substring(2, 6));
      return [_srgbToLinear(v >> 16), _srgbToLinear((v >> 8) & 255), _srgbToLinear(v & 255)];
    }
    final v = _decode83(hash.substring(4 + i * 2, 6 + i * 2));
    return [
      _signPow(((v ~/ (19 * 19)) - 9) / 9, 2) * maxValue,
      _signPow((((v ~/ 19) % 19) - 9) / 9, 2) * maxValue,
      _signPow(((v % 19) - 9) / 9, 2) * maxValue,
    ];
  });

  final out = <Color>[];
  for (var y = 0; y < height; y++) {
    for (var x = 0; x < width; x++) {
      var r = 0.0, g = 0.0, b = 0.0;
      for (var j = 0; j < numY; j++) {
        for (var i = 0; i < numX; i++) {
          final basis = math.cos(math.pi * x * i / width) * math.cos(math.pi * y * j / height);
          final c = colors[i + j * numX];
          r += c[0] * basis;
          g += c[1] * basis;
          b += c[2] * basis;
        }
      }
      out.add(Color.fromARGB(255, _linearToSrgb(r), _linearToSrgb(g), _linearToSrgb(b)));
    }
  }
  return out;
}

/// Placeholder gambar dari BlurHash, digambar sebagai grid kecil yang direntangkan. Tanpa package tambahan.
class BlurHashBox extends StatelessWidget {
  const BlurHashBox({super.key, required this.hash});

  final String? hash;

  static const _w = 12;
  static const _h = 15;

  @override
  Widget build(BuildContext context) {
    List<Color>? grid;
    if (hash != null) {
      try {
        grid = decodeBlurHash(hash!, _w, _h);
      } on FormatException {
        grid = null;
      }
    }
    return ClipRect(
      child: ImageFiltered(
        imageFilter: ImageFilter.blur(sigmaX: 14, sigmaY: 14, tileMode: TileMode.clamp),
        child: CustomPaint(painter: _GridPainter(grid), child: const SizedBox.expand()),
      ),
    );
  }
}

class _GridPainter extends CustomPainter {
  _GridPainter(this.grid);

  final List<Color>? grid;

  @override
  void paint(Canvas canvas, Size size) {
    final g = grid;
    if (g == null) {
      canvas.drawRect(Offset.zero & size, Paint()..color = const Color(0xFF263238));
      return;
    }
    final cw = size.width / BlurHashBox._w;
    final ch = size.height / BlurHashBox._h;
    final paint = Paint()..isAntiAlias = false;
    for (var y = 0; y < BlurHashBox._h; y++) {
      for (var x = 0; x < BlurHashBox._w; x++) {
        paint.color = g[y * BlurHashBox._w + x];
        canvas.drawRect(Rect.fromLTWH(x * cw, y * ch, cw + 1, ch + 1), paint);
      }
    }
  }

  @override
  bool shouldRepaint(_GridPainter old) => old.grid != grid;
}
