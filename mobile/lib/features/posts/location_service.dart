import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';

import '../../core/geo/geohash.dart';

/// Lokasi kasar untuk post dan feed Sekitar. Hanya geohash yang keluar dari device.
abstract interface class LocationService {
  /// Geohash 6 karakter posisi sekarang, atau `null` bila izin ditolak / layanan lokasi mati.
  Future<String?> currentCell();
}

final locationServiceProvider = Provider<LocationService>((ref) => GeolocatorLocationService());

class GeolocatorLocationService implements LocationService {
  @override
  Future<String?> currentCell() async {
    try {
      if (!await Geolocator.isLocationServiceEnabled()) return null;
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied || permission == LocationPermission.deniedForever) return null;
      final pos = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.low, timeLimit: Duration(seconds: 15)),
      );
      return geohashEncode(pos.latitude, pos.longitude);
    } catch (_) {
      return null;
    }
  }
}
