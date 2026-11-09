package app.senyap.identity;

import java.util.Locale;
import java.util.Optional;

/**
 * Gender pilihan user (functional spec FS-01). Menentukan katalog kata alias saat diacak dan ditampilkan
 * bersama alias. Nilai di API/DB: {@code male} | {@code female} | {@code unspecified}.
 */
public enum Gender {
    MALE, FEMALE, UNSPECIFIED;

    /** Nilai di API dan kolom {@code users.gender}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Gender> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (Gender g : values()) {
            if (g.code().equals(code)) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }
}
