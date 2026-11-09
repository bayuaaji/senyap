package app.senyap.identity.internal;

import app.senyap.identity.Gender;
import app.senyap.identity.internal.AliasCatalog.AliasColor;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Mengacak alias {@code {kata1} {kata2} #tag} dari katalog gender user yang belum dipakai (unik global pada
 * {@code (alias_name, alias_tag)}). Kalau bentrok, tag diacak ulang (functional spec §1.1).
 * Pemeriksaan di sini hanya optimis; constraint {@code users_alias_uq} tetap penentu akhir.
 */
@Component
class AliasGenerator {

    record Alias(Gender gender, String first, AliasColor second, String tag) {
        String name() {
            return AliasCatalog.aliasName(first, second);
        }
    }

    static final int MAX_TAG_TRIES = 20;

    private final SecureRandom random = new SecureRandom();
    private final ModuleDatabase db;

    AliasGenerator(@Qualifier("identityDatabase") ModuleDatabase db) {
        this.db = db;
    }

    /**
     * @param first  null = acak; harus ada di katalog {@code gender}
     * @param second null = acak; harus ada di katalog {@code gender}
     */
    Alias generate(Gender gender, String first, String second) {
        var words = AliasCatalog.words(gender);
        if (first != null && !words.isFirst(first)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kata pertama tidak ada di daftar.");
        }
        AliasColor color = null;
        if (second != null) {
            color = words.second(second)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Kata kedua tidak ada di daftar."));
        }
        for (int i = 0; i < MAX_TAG_TRIES; i++) {
            var a = first != null ? first : words.first().get(random.nextInt(words.first().size()));
            var c = color != null ? color : words.second().get(random.nextInt(words.second().size()));
            var candidate = new Alias(words.gender(), a, c, randomTag());
            if (isFree(candidate.name(), candidate.tag())) {
                return candidate;
            }
        }
        throw new ApiException(ErrorCode.INTERNAL, "Alias tidak tersedia. Coba kombinasi lain.");
    }

    String randomTag() {
        return String.format("%04d", random.nextInt(10_000));
    }

    boolean isFree(String name, String tag) {
        return db.jdbc().sql("SELECT NOT EXISTS (SELECT 1 FROM users WHERE alias_name = :n AND alias_tag = :t)")
                .param("n", name).param("t", tag).query(Boolean.class).single();
    }
}
