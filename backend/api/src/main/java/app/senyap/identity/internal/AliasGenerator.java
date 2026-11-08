package app.senyap.identity.internal;

import app.senyap.identity.internal.AliasCatalog.AliasColor;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Mengacak alias {@code {hewan} {warna} #tag} yang belum dipakai (unik global pada
 * {@code (alias_name, alias_tag)}). Kalau bentrok, tag diacak ulang (functional spec §1.1).
 * Pemeriksaan di sini hanya optimis; constraint {@code users_alias_uq} tetap penentu akhir.
 */
@Component
class AliasGenerator {

    record Alias(String animal, AliasColor color, String tag) {
        String name() {
            return AliasCatalog.aliasName(animal, color);
        }
    }

    static final int MAX_TAG_TRIES = 20;

    private final SecureRandom random = new SecureRandom();
    private final ModuleDatabase db;

    AliasGenerator(@Qualifier("identityDatabase") ModuleDatabase db) {
        this.db = db;
    }

    /** @param animal null = acak; @param colorName null = acak. Nilai harus ada di {@link AliasCatalog}. */
    Alias generate(String animal, String colorName) {
        if (animal != null && !AliasCatalog.isAnimal(animal)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Hewan tidak ada di daftar.");
        }
        AliasColor color = null;
        if (colorName != null) {
            color = AliasCatalog.color(colorName)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Warna tidak ada di daftar."));
        }
        for (int i = 0; i < MAX_TAG_TRIES; i++) {
            var a = animal != null ? animal : AliasCatalog.ANIMALS.get(random.nextInt(AliasCatalog.ANIMALS.size()));
            var c = color != null ? color : AliasCatalog.COLORS.get(random.nextInt(AliasCatalog.COLORS.size()));
            var candidate = new Alias(a, c, randomTag());
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
