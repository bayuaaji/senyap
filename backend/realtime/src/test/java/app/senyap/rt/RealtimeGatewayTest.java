package app.senyap.rt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.senyap.contracts.auth.AccessTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;

/**
 * Gateway: handshake hanya dengan access token yang lolos verifikasi JWKS (aturan M12). JWKS api diganti
 * key uji lewat bean {@link JWKSource}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeGatewayTest {

    static final ECKey KEY = generate("uji");
    static final ECKey OTHER_KEY = generate("uji");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwks {
        @Bean
        @Primary
        JWKSource<SecurityContext> testJwkSource() {
            return new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK()));
        }
    }

    static final org.testcontainers.containers.GenericContainer<?> REDIS =
            new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void redis(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    @Test
    void healthUp() {
        String body = WebClient.create("http://localhost:" + port).get().uri("/actuator/health")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void pingDibalasPongDanFrameLainError() {
        List<String> received = new ArrayList<>();
        connect(token(KEY, Instant.now().plusSeconds(60)), received);

        assertThat(received).hasSize(3);
        assertThat(received.get(0)).contains("\"t\":\"pong\"").contains("\"id\":\"f1\"");
        assertThat(received.get(1)).contains("\"t\":\"error\"").contains("\"ref_id\":\"f2\"").contains("VALIDATION_FAILED");
        assertThat(received.get(2)).contains("\"t\":\"error\"").contains("VALIDATION_FAILED");
    }

    @Test
    void handshakeTanpaTokenAtauTokenTidakValidDitolak() {
        assertThatThrownBy(() -> connect(null, new ArrayList<>())).hasMessageContaining("401");
        assertThatThrownBy(() -> connect(token(OTHER_KEY, Instant.now().plusSeconds(60)), new ArrayList<>()))
                .hasMessageContaining("401");
        assertThatThrownBy(() -> connect(token(KEY, Instant.now().minusSeconds(120)), new ArrayList<>()))
                .hasMessageContaining("401");
    }

    private void connect(String token, List<String> received) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        new ReactorNettyWebSocketClient().execute(URI.create("ws://localhost:" + port + "/v1/ws"), headers, session ->
                        session.send(Flux.just(
                                        session.textMessage("{\"t\":\"ping\",\"id\":\"f1\",\"d\":{}}"),
                                        session.textMessage("{\"t\":\"msg.send\",\"id\":\"f2\",\"d\":{}}"),
                                        session.textMessage("bukan json")))
                                .thenMany(session.receive().map(WebSocketMessage::getPayloadAsText).take(3)
                                        .doOnNext(received::add))
                                .then())
                .block(Duration.ofSeconds(10));
    }

    static String token(ECKey key, Instant expiresAt) {
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(),
                    new JWTClaimsSet.Builder()
                            .issuer(AccessTokens.ISSUER).audience(AccessTokens.AUDIENCE)
                            .subject(UUID.randomUUID().toString())
                            .claim(AccessTokens.CLAIM_DEVICE, "123")
                            .issueTime(Date.from(expiresAt.minusSeconds(900)))
                            .expirationTime(Date.from(expiresAt))
                            .build());
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ECKey generate(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
