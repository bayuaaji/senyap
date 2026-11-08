package app.senyap.auth.internal;

import java.time.LocalDate;

/**
 * Membuat private key ES256 baru untuk {@code SENYAP_AUTH_SIGNING_JWK}. Jalankan dari IDE atau:
 * {@code java -cp api/target/classes:$(cat cp.txt) app.senyap.auth.internal.GenerateSigningKey}.
 * Simpan keluarannya di secret manager, jangan di repo.
 */
final class GenerateSigningKey {

    private GenerateSigningKey() {
    }

    public static void main(String[] args) {
        System.out.println(SigningKeys.generate("senyap-" + LocalDate.now()).toJSONString());
    }
}
