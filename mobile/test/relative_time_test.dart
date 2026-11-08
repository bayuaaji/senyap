import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/core/geo/geohash.dart';
import 'package:senyap/core/time/relative_time.dart';
import 'package:senyap/features/posts/blurhash.dart';

void main() {
  final now = DateTime.utc(2026, 11, 8, 12);

  group('formatRelative (functional spec §4.1)', () {
    final cases = <Duration, String>{
      const Duration(seconds: 30): 'baru saja',
      const Duration(seconds: -90): 'baru saja',
      const Duration(minutes: 1): '1 menit yang lalu',
      const Duration(minutes: 59, seconds: 59): '59 menit yang lalu',
      const Duration(hours: 1, minutes: 59): '1 jam yang lalu',
      const Duration(hours: 23): '23 jam yang lalu',
      const Duration(days: 1): '1 hari yang lalu',
      const Duration(days: 29): '29 hari yang lalu',
      const Duration(days: 30): '1 bulan yang lalu',
      const Duration(days: 364): '11 bulan yang lalu',
      const Duration(days: 365): '1 tahun yang lalu',
      const Duration(days: 800): '2 tahun yang lalu',
    };
    cases.forEach((ago, text) {
      test('$ago → $text', () => expect(formatRelative(now.subtract(ago), now), text));
    });

    test('interval pembaruan: tiap menit < 1 jam, lalu tiap jam', () {
      expect(relativeRefreshInterval(now.subtract(const Duration(minutes: 5)), now), const Duration(minutes: 1));
      expect(relativeRefreshInterval(now.subtract(const Duration(hours: 2)), now), const Duration(hours: 1));
    });
  });

  testWidgets('RelativeTimeText memperbarui teks setelah 1 menit', (tester) async {
    var clock = now;
    await tester.pumpWidget(Directionality(
      textDirection: TextDirection.ltr,
      child: RelativeTimeText(now.subtract(const Duration(minutes: 2)), clock: () => clock),
    ));
    expect(find.text('2 menit yang lalu'), findsOneWidget);
    clock = now.add(const Duration(minutes: 1));
    await tester.pump(const Duration(minutes: 1));
    expect(find.text('3 menit yang lalu'), findsOneWidget);
  });

  test('geohash sama dengan server (Yogyakarta)', () {
    expect(geohashEncode(-7.7956, 110.3695, precision: 7), 'qqw7xsb');
  });

  test('blurhash: dekode hash satu warna (putih) dan tolak hash rusak', () {
    final grid = decodeBlurHash('00TSUA', 2, 2);
    expect(grid, hasLength(4));
    expect(grid.first, const Color(0xFFFFFFFF));
    expect(() => decodeBlurHash('LEHV6n', 2, 2), throwsFormatException);
    expect(decodeBlurHash('LEHV6nWB2yk8pyo0adR*.7kCMdnj', 4, 3), hasLength(12));
  });
}
