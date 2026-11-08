package app.senyap.safety.internal;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalisasi untuk pencocokan daftar kata: huruf kecil, diakritik dibuang, angka/simbol mirip huruf diganti
 * (0→o 1→i 3→e 4→a 5→s 7→t @→a $→s), selain huruf/angka jadi spasi, huruf berulang diringkas ("sangeee" →
 * "sange"), spasi dirapikan. Hasil dibungkus spasi supaya frasa dicocokkan per kata utuh.
 */
final class TextNormalizer {

    private TextNormalizer() {
    }

    static String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        var out = new StringBuilder(s.length() + 2).append(' ');
        char prev = ' ';
        for (int i = 0; i < s.length(); i++) {
            char c = switch (s.charAt(i)) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> s.charAt(i);
            };
            if (!Character.isLetterOrDigit(c)) {
                c = ' ';
            }
            if (c == prev) {
                continue;
            }
            out.append(c);
            prev = c;
        }
        if (prev != ' ') {
            out.append(' ');
        }
        return out.toString();
    }

    /** {@code true} bila frasa (sudah dinormalisasi, berbungkus spasi) muncul sebagai kata utuh. */
    static boolean contains(String normalizedText, String normalizedPhrase) {
        return normalizedText.contains(normalizedPhrase);
    }
}
