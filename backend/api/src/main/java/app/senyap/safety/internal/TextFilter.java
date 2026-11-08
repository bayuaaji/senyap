package app.senyap.safety.internal;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Lapisan pertama FS-08: daftar kata terlarang (semua teks) dan pola doxxing (hanya konten publik: post,
 * balasan). Pesan penolakan ramah dan tidak menyebut kata yang cocok.
 */
@Component
class TextFilter {

    static final String BLOCKED_MESSAGE =
            "Tulisanmu memuat kata yang tidak diizinkan di Senyap. Coba ubah kalimatnya, ya.";
    static final String DOXXING_MESSAGE =
            "Demi keamanan, jangan tulis nomor HP, NIK, email, atau alamat di tempat umum. Hapus dulu bagian itu, ya.";

    /** Nomor seluler Indonesia: 08…, +628…, 628…, boleh dipisah spasi/titik/strip. */
    static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?62|0)[\\s.\\-]?8(?:[\\s.\\-]?\\d){7,11}(?!\\d)");
    /** NIK: 16 digit (boleh dipisah spasi/titik/strip). */
    static final Pattern NIK = Pattern.compile("(?<!\\d)\\d(?:[\\s.\\-]?\\d){15}(?!\\d)");
    static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    /** Alamat: "Jl./Jalan/Gg./Gang <nama> No. <angka>" atau "RT 03/RW 05". */
    static final Pattern ADDRESS = Pattern.compile(
            "\\b(?:jl|jln|jalan|gg|gang|komplek|kompleks|perum|perumahan)\\.?\\s+[\\p{L}0-9 .'\\-]{2,40}?\\s*"
                    + "\\b(?:no|nomor|nmr|blok)\\.?\\s*[0-9a-z]{0,3}\\d+"
                    + "|\\brt\\s*\\.?\\s*\\d{1,3}\\s*/\\s*rw\\s*\\.?\\s*\\d{1,3}\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final List<String> blocked;

    TextFilter(SafetyProperties props) {
        this.blocked = TermFiles.lines(props.blockedWordsFile()).stream().map(TextNormalizer::normalize).toList();
    }

    /** Pesan penolakan bila teks memuat kata terlarang. */
    Optional<String> blockedWord(String normalizedText) {
        for (String w : blocked) {
            if (TextNormalizer.contains(normalizedText, w)) {
                return Optional.of(BLOCKED_MESSAGE);
            }
        }
        return Optional.empty();
    }

    /** Pesan penolakan bila teks publik memuat nomor HP, NIK, email, atau alamat. */
    Optional<String> doxxing(String text) {
        if (PHONE.matcher(text).find() || NIK.matcher(text).find() || EMAIL.matcher(text).find()
                || ADDRESS.matcher(text).find()) {
            return Optional.of(DOXXING_MESSAGE);
        }
        return Optional.empty();
    }
}
