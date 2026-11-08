package app.senyap.shared.security;

import app.senyap.contracts.auth.AccessTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Memverifikasi access token hanya dengan public key dari JWKS (aturan M12): tanda tangan ES256,
 * {@code iss}, {@code aud}, {@code exp}, dan klaim wajib {@code sub} (public_id) + {@code did}.
 */
public final class AccessTokenVerifier {

    public record VerifiedToken(UUID publicId, long deviceId) {
    }

    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public AccessTokenVerifier(JWKSource<SecurityContext> jwks) {
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, jwks));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                AccessTokens.AUDIENCE,
                new JWTClaimsSet.Builder().issuer(AccessTokens.ISSUER).build(),
                Set.of("sub", "exp", "iat", AccessTokens.CLAIM_DEVICE)));
    }

    public Optional<VerifiedToken> verify(String token) {
        try {
            var claims = processor.process(token, null);
            return Optional.of(new VerifiedToken(
                    UUID.fromString(claims.getSubject()),
                    Long.parseLong(claims.getStringClaim(AccessTokens.CLAIM_DEVICE))));
        } catch (BadJOSEException | com.nimbusds.jose.JOSEException | java.text.ParseException
                 | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
