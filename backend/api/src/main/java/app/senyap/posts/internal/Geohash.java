package app.senyap.posts.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Geohash (base32) secukupnya untuk lokasi kasar: validasi, titik tengah sel, dan 8 sel tetangga.
 * Presisi 5 karakter ≈ 4,9 × 4,9 km. Server hanya menerima geohash dari client, tidak pernah lat/lng.
 */
final class Geohash {

    static final int PRECISION = 5;
    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private Geohash() {
    }

    /** Geohash 5 karakter huruf kecil, atau {@code null} bila input bukan geohash valid (≥ 5 karakter). */
    static String normalize(String input) {
        if (input == null || input.length() < PRECISION || input.length() > 12) {
            return null;
        }
        String g = input.toLowerCase(Locale.ROOT);
        for (int i = 0; i < g.length(); i++) {
            if (BASE32.indexOf(g.charAt(i)) < 0) {
                return null;
            }
        }
        return g.substring(0, PRECISION);
    }

    /** {lat, lng} titik tengah sel. */
    static double[] center(String hash) {
        double[] b = bounds(hash);
        return new double[] {(b[0] + b[1]) / 2, (b[2] + b[3]) / 2};
    }

    /** Sel itu sendiri + 8 tetangga (lebih sedikit di dekat kutub/antimeridian bila bertumpuk). */
    static List<String> withNeighbors(String hash) {
        double[] b = bounds(hash);
        double dLat = b[1] - b[0];
        double dLng = b[3] - b[2];
        double lat = (b[0] + b[1]) / 2;
        double lng = (b[2] + b[3]) / 2;
        var cells = new LinkedHashSet<String>();
        cells.add(hash);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                double nLat = lat + dy * dLat;
                if (nLat > 90 || nLat < -90) {
                    continue;
                }
                double nLng = lng + dx * dLng;
                if (nLng > 180) {
                    nLng -= 360;
                } else if (nLng < -180) {
                    nLng += 360;
                }
                cells.add(encode(nLat, nLng, hash.length()));
            }
        }
        return new ArrayList<>(cells);
    }

    static String encode(double lat, double lng, int precision) {
        double latMin = -90;
        double latMax = 90;
        double lngMin = -180;
        double lngMax = 180;
        var sb = new StringBuilder();
        boolean even = true;
        int bit = 0;
        int ch = 0;
        while (sb.length() < precision) {
            if (even) {
                double mid = (lngMin + lngMax) / 2;
                if (lng >= mid) {
                    ch = (ch << 1) | 1;
                    lngMin = mid;
                } else {
                    ch <<= 1;
                    lngMax = mid;
                }
            } else {
                double mid = (latMin + latMax) / 2;
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
                sb.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return sb.toString();
    }

    /** {latMin, latMax, lngMin, lngMax}. */
    private static double[] bounds(String hash) {
        double latMin = -90;
        double latMax = 90;
        double lngMin = -180;
        double lngMax = 180;
        boolean even = true;
        for (int i = 0; i < hash.length(); i++) {
            int cd = BASE32.indexOf(hash.charAt(i));
            for (int mask = 16; mask > 0; mask >>= 1) {
                boolean on = (cd & mask) != 0;
                if (even) {
                    double mid = (lngMin + lngMax) / 2;
                    if (on) {
                        lngMin = mid;
                    } else {
                        lngMax = mid;
                    }
                } else {
                    double mid = (latMin + latMax) / 2;
                    if (on) {
                        latMin = mid;
                    } else {
                        latMax = mid;
                    }
                }
                even = !even;
            }
        }
        return new double[] {latMin, latMax, lngMin, lngMax};
    }
}
