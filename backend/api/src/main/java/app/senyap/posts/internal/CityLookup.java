package app.senyap.posts.internal;

import java.util.List;

/**
 * Reverse-geocode kasar tanpa layanan eksternal: kota terdekat (≤ {@value #MAX_KM} km) dari titik tengah sel
 * geohash. Daftar kota besar Indonesia dengan koordinat perkiraan; cukup untuk label "Yogyakarta" di post.
 * Bila perlu cakupan penuh, ganti dengan dataset batas wilayah (BPS) di Fase 5.
 */
final class CityLookup {

    static final double MAX_KM = 35;

    private record City(String name, double lat, double lng) {
    }

    private static final List<City> CITIES = List.of(
            new City("Jakarta", -6.2088, 106.8456), new City("Bogor", -6.5971, 106.8060),
            new City("Depok", -6.4025, 106.7942), new City("Tangerang", -6.1783, 106.6319),
            new City("Tangerang Selatan", -6.2886, 106.7179), new City("Bekasi", -6.2383, 106.9756),
            new City("Karawang", -6.3227, 107.3376), new City("Purwakarta", -6.5569, 107.4433),
            new City("Bandung", -6.9175, 107.6191), new City("Cimahi", -6.8722, 107.5425),
            new City("Sukabumi", -6.9277, 106.9300), new City("Cianjur", -6.8168, 107.1425),
            new City("Garut", -7.2279, 107.9087), new City("Tasikmalaya", -7.3274, 108.2207),
            new City("Cirebon", -6.7320, 108.5523), new City("Serang", -6.1200, 106.1503),
            new City("Cilegon", -6.0025, 106.0111), new City("Semarang", -6.9667, 110.4167),
            new City("Surakarta", -7.5755, 110.8243), new City("Yogyakarta", -7.7956, 110.3695),
            new City("Magelang", -7.4705, 110.2178), new City("Salatiga", -7.3305, 110.5084),
            new City("Kudus", -6.8048, 110.8405), new City("Tegal", -6.8694, 109.1402),
            new City("Pekalongan", -6.8886, 109.6753), new City("Purwokerto", -7.4245, 109.2302),
            new City("Surabaya", -7.2575, 112.7521), new City("Sidoarjo", -7.4478, 112.7183),
            new City("Mojokerto", -7.4722, 112.4336), new City("Pasuruan", -7.6453, 112.9075),
            new City("Malang", -7.9666, 112.6326), new City("Batu", -7.8672, 112.5239),
            new City("Blitar", -8.0955, 112.1609), new City("Kediri", -7.8480, 112.0178),
            new City("Madiun", -7.6298, 111.5239), new City("Jember", -8.1845, 113.6681),
            new City("Probolinggo", -7.7543, 113.2159), new City("Banyuwangi", -8.2192, 114.3691),
            new City("Denpasar", -8.6705, 115.2126), new City("Mataram", -8.5833, 116.1167),
            new City("Kupang", -10.1772, 123.6070), new City("Banda Aceh", 5.5483, 95.3238),
            new City("Medan", 3.5952, 98.6722), new City("Binjai", 3.6001, 98.4854),
            new City("Pematangsiantar", 2.9595, 99.0687), new City("Padang", -0.9471, 100.4172),
            new City("Bukittinggi", -0.3055, 100.3692), new City("Pekanbaru", 0.5071, 101.4478),
            new City("Batam", 1.0456, 104.0305), new City("Tanjungpinang", 0.9186, 104.4554),
            new City("Jambi", -1.6101, 103.6131), new City("Palembang", -2.9761, 104.7754),
            new City("Bengkulu", -3.8004, 102.2655), new City("Bandar Lampung", -5.3971, 105.2668),
            new City("Pangkalpinang", -2.1291, 106.1090), new City("Pontianak", -0.0263, 109.3425),
            new City("Singkawang", 0.9060, 108.9847), new City("Palangka Raya", -2.2096, 113.9108),
            new City("Banjarmasin", -3.3186, 114.5944), new City("Banjarbaru", -3.4572, 114.8103),
            new City("Balikpapan", -1.2379, 116.8529), new City("Samarinda", -0.5022, 117.1536),
            new City("Tarakan", 3.3000, 117.6333), new City("Manado", 1.4748, 124.8421),
            new City("Gorontalo", 0.5435, 123.0568), new City("Palu", -0.8917, 119.8707),
            new City("Makassar", -5.1477, 119.4327), new City("Parepare", -4.0135, 119.6255),
            new City("Kendari", -3.9985, 122.5130), new City("Ambon", -3.6954, 128.1814),
            new City("Ternate", 0.7893, 127.3772), new City("Sorong", -0.8762, 131.2558),
            new City("Manokwari", -0.8615, 134.0620), new City("Jayapura", -2.5337, 140.7181),
            new City("Merauke", -8.4932, 140.4018));

    private CityLookup() {
    }

    /** Nama kota terdekat dari sel geohash, atau {@code null} bila tidak ada kota dalam {@value #MAX_KM} km. */
    static String nearest(String geohash) {
        double[] c = Geohash.center(geohash);
        City best = null;
        double bestKm = Double.MAX_VALUE;
        for (City city : CITIES) {
            double km = haversineKm(c[0], c[1], city.lat(), city.lng());
            if (km < bestKm) {
                bestKm = km;
                best = city;
            }
        }
        return best != null && bestKm <= MAX_KM ? best.name() : null;
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
