import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';

import '../../core/geo/geohash.dart';

enum LocationStatus {
  ok,

  /// Izin ditolak, masih bisa diminta lagi.
  denied,

  /// Izin ditolak permanen: hanya bisa diubah dari Pengaturan HP.
  deniedForever,

  /// Layanan lokasi (GPS) di HP mati.
  serviceDisabled,

  /// Posisi tidak didapat (timeout, error platform).
  failed,
}

class LocationResult {
  const LocationResult(this.status, [this.cell]);

  const LocationResult.ok(String cell) : this(LocationStatus.ok, cell);

  final LocationStatus status;

  /// Geohash 6 karakter; hanya ada bila [status] = [LocationStatus.ok].
  final String? cell;

  bool get isOk => status == LocationStatus.ok && cell != null;
}

/// Lokasi kasar untuk post dan feed Sekitar. Hanya geohash yang keluar dari device.
abstract interface class LocationService {
  /// Posisi sekarang sebagai geohash, beserta alasan bila gagal. Meminta izin bila belum pernah ditanya.
  Future<LocationResult> currentCell();

  /// Halaman pengaturan app (untuk izin yang ditolak permanen).
  Future<bool> openAppSettings();

  /// Pengaturan layanan lokasi HP (untuk GPS yang mati).
  Future<bool> openLocationSettings();
}

final locationServiceProvider = Provider<LocationService>((ref) => GeolocatorLocationService());

class GeolocatorLocationService implements LocationService {
  @override
  Future<LocationResult> currentCell() async {
    try {
      if (!await Geolocator.isLocationServiceEnabled()) {
        return const LocationResult(LocationStatus.serviceDisabled);
      }
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.deniedForever) return const LocationResult(LocationStatus.deniedForever);
      if (permission == LocationPermission.denied) return const LocationResult(LocationStatus.denied);

      Position? pos;
      try {
        pos = await Geolocator.getCurrentPosition(
          locationSettings: const LocationSettings(accuracy: LocationAccuracy.low, timeLimit: Duration(seconds: 10)),
        );
      } on TimeoutException {
        pos = null;
      } on LocationServiceDisabledException {
        return const LocationResult(LocationStatus.serviceDisabled);
      } catch (_) {
        pos = null;
      }
      // Cadangan: posisi terakhir yang diketahui (cukup untuk geohash setingkat ±5 km).
      pos ??= await Geolocator.getLastKnownPosition();
      if (pos == null) return const LocationResult(LocationStatus.failed);
      return LocationResult.ok(geohashEncode(pos.latitude, pos.longitude));
    } catch (_) {
      return const LocationResult(LocationStatus.failed);
    }
  }

  @override
  Future<bool> openAppSettings() => Geolocator.openAppSettings();

  @override
  Future<bool> openLocationSettings() => Geolocator.openLocationSettings();
}
