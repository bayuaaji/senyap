import 'package:flutter/material.dart';

import '../../app/ui/ui.dart';
import 'location_service.dart';

enum LocationChoice { openSettings, retry, continueWithout, cancel }

String locationProblemMessage(LocationStatus status) => switch (status) {
      LocationStatus.deniedForever =>
        'Akses lokasi untuk Senyap ditolak. Izinkan lokasi di Pengaturan HP supaya kota bisa ditampilkan.',
      LocationStatus.serviceDisabled => 'Layanan lokasi di HP-mu sedang mati. Nyalakan di Pengaturan.',
      LocationStatus.denied => 'Kamu belum mengizinkan akses lokasi.',
      LocationStatus.failed => 'Lokasimu belum bisa ditemukan. Coba lagi sebentar lagi.',
      LocationStatus.ok => '',
    };

/// Dialog saat lokasi tidak didapat. Izin ditolak permanen / layanan mati → "Buka Pengaturan";
/// lainnya → "Coba lagi". [withoutLabel] (mis. "Kirim tanpa lokasi") hanya tampil bila diisi.
Future<LocationChoice> showLocationProblem(
  BuildContext context,
  LocationStatus status, {
  String? withoutLabel,
}) async {
  final needsSettings = status == LocationStatus.deniedForever || status == LocationStatus.serviceDisabled;
  final choice = await showDialog<LocationChoice>(
    context: context,
    builder: (context) => SenyapDialog(
      key: const Key('location-dialog'),
      icon: Icons.location_off_outlined,
      title: 'Lokasi tidak tersedia',
      message: locationProblemMessage(status),
      content: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        const SizedBox(height: Space.xs),
        if (needsSettings)
          FilledButton(
            key: const Key('location-settings'),
            onPressed: () => Navigator.pop(context, LocationChoice.openSettings),
            child: const Text('Buka Pengaturan'),
          )
        else
          FilledButton(
            key: const Key('location-retry'),
            onPressed: () => Navigator.pop(context, LocationChoice.retry),
            child: const Text('Coba lagi'),
          ),
        if (withoutLabel != null) ...[
          const SizedBox(height: Space.xs),
          OutlinedButton(
            key: const Key('location-without'),
            onPressed: () => Navigator.pop(context, LocationChoice.continueWithout),
            child: Text(withoutLabel),
          ),
        ],
        const SizedBox(height: Space.xxs),
        TextButton(
          key: const Key('location-cancel'),
          style: TextButton.styleFrom(foregroundColor: context.colors.textSecondary),
          onPressed: () => Navigator.pop(context, LocationChoice.cancel),
          child: const Text('Batal'),
        ),
      ]),
    ),
  );
  return choice ?? LocationChoice.cancel;
}

/// Membuka pengaturan yang sesuai dengan [status].
Future<void> openSettingsFor(LocationService service, LocationStatus status) =>
    status == LocationStatus.serviceDisabled ? service.openLocationSettings() : service.openAppSettings();
