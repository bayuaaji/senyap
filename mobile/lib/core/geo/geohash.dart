/// Geohash base32. Lokasi dikirim ke server hanya sebagai geohash (server memotong ke 5 karakter ≈ 5 km),
/// tidak pernah lat/lng.
String geohashEncode(double lat, double lng, {int precision = 6}) {
  const base32 = '0123456789bcdefghjkmnpqrstuvwxyz';
  var latMin = -90.0, latMax = 90.0, lngMin = -180.0, lngMax = 180.0;
  final out = StringBuffer();
  var even = true;
  var bit = 0;
  var ch = 0;
  while (out.length < precision) {
    if (even) {
      final mid = (lngMin + lngMax) / 2;
      if (lng >= mid) {
        ch = (ch << 1) | 1;
        lngMin = mid;
      } else {
        ch <<= 1;
        lngMax = mid;
      }
    } else {
      final mid = (latMin + latMax) / 2;
      if (lat >= mid) {
        ch = (ch << 1) | 1;
        latMin = mid;
      } else {
        ch <<= 1;
        latMax = mid;
      }
    }
    even = !even;
    if (++bit == 5) {
      out.write(base32[ch]);
      bit = 0;
      ch = 0;
    }
  }
  return out.toString();
}
