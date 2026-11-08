/// Konfigurasi build. Ganti lewat `--dart-define`, misalnya:
/// `flutter run --dart-define=SENYAP_API_URL=http://10.0.2.2:8180` (emulator Android).
abstract final class AppConfig {
  static const apiBaseUrl = String.fromEnvironment(
    'SENYAP_API_URL',
    defaultValue: 'http://localhost:8180',
  );
}
