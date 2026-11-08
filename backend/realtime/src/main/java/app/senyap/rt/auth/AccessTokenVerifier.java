package app.senyap.rt.auth;

import app.senyap.contracts.auth.AccessTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jose.KeySourceException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;
import java.util.UUID;

/**
 * Verifikasi access token di gateway hanya dengan JWKS publik api (aturan M12). Kontrak klaim ada di
 * {@link AccessTokens}. Gateway tidak pernah tahu user ID internal; identitas sesi = {@code public_id}.
 * Pemanggilan bisa blocking (mengambil JWKS lewat HTTP), jadi jalankan di scheduler boundedElastic.
 */
public final class AccessTokenVerifier {

    public record Principal(UUID publicId, long deviceId) {
    }

    private static final Logger log = LoggerFactory.getLogger(AccessTokenVerifier.class);

    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public AccessTokenVerifier(JWKSource<SecurityContext> jwks) {
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, jwks));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                AccessTokens.AUDIENCE,
                new JWTClaimsSet.Builder().issuer(AccessTokens.ISSUER).build(),
                Set.of("sub", "exp", "iat", AccessTokens.CLAIM_DEVICE)));
    }

    public Optional<Principal> verify(String token) {
        try {
            var claims = processor.process(token, null);
            return Optional.of(new Principal(UUID.fromString(claims.getSubject()),
                    Long.parseLong(claims.getStringClaim(AccessTokens.CLAIM_DEVICE))));
        } catch (KeySourceException e) {
            // JWKS api tidak bisa diambil: masalah operasional, bukan token buruk. Token tidak ditulis ke log.
            log.warn("JWKS tidak bisa diambil: {}", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
