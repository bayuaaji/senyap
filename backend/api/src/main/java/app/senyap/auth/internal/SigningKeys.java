package app.senyap.auth.internal;

import app.senyap.shared.security.LocalJwks;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.text.ParseException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Key penandatangan access token (ES256). Public key-nya dipublikasikan sebagai JWKS
 * ({@code /.well-known/jwks.json}) supaya modul lain, gateway realtime, dan service hasil pecahan
 * memverifikasi token tanpa memanggil modul auth (aturan M12).
 *
 * <p>Rotasi key (Fase 5): tambahkan key baru ke JWKS dulu, tunggu cache verifier kedaluwarsa, baru
 * tandatangani dengan key baru.
 */
@Component
class SigningKeys implements LocalJwks {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final ECKey signingKey;
    private final JWKSet publicJwks;

    SigningKeys(AuthProperties props) {
        this.signingKey = load(props);
        this.publicJwks = new JWKSet(signingKey.toPublicJWK());
    }

    private static ECKey load(AuthProperties props) {
        String jwk = props.signingJwk();
        if (jwk != null && !jwk.isBlank()) {
            try {
                var key = ECKey.parse(jwk);
                if (!Curve.P_256.equals(key.getCurve()) || !key.isPrivate() || key.getKeyID() == null) {
                    throw new IllegalStateException("senyap.auth.signing-jwk harus private key EC P-256 dengan kid");
                }
                return key;
            } catch (ParseException e) {
                throw new IllegalStateException("senyap.auth.signing-jwk bukan JWK yang valid", e);
            }
        }
        if (!props.ephemeralSigningKey()) {
            throw new IllegalStateException("senyap.auth.signing-jwk wajib diisi (env SENYAP_AUTH_SIGNING_JWK / KMS)");
        }
        log.warn("Memakai signing key sementara (hanya local/test); access token tidak valid lagi setelah restart");
        return generate("ephemeral-" + UUID.randomUUID());
    }

    static ECKey generate(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).keyUse(KeyUse.SIGNATURE).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    ECKey signingKey() {
        return signingKey;
    }

    @Override
    public JWKSet publicJwks() {
        return publicJwks;
    }
}
