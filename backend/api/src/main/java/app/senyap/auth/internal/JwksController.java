package app.senyap.auth.internal;

import app.senyap.contracts.auth.AccessTokens;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** JWKS publik (aturan M12). Hanya public key; tidak butuh autentikasi. */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class JwksController {

    private final SigningKeys keys;

    JwksController(SigningKeys keys) {
        this.keys = keys;
    }

    @GetMapping(value = AccessTokens.JWKS_PATH, produces = "application/jwk-set+json")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keys.publicJwks().toJSONObject(true));
    }
}
