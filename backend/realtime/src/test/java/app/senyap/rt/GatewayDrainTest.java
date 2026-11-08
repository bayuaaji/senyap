package app.senyap.rt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.senyap.contracts.ws.FrameType;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Graceful drain saat deploy: shutdown menutup semua sesi dengan 1012 (Service Restart) secara bertahap,
 * handshake baru selama drain langsung ditolak 1012, dan presence node ini dibersihkan.
 */
class GatewayDrainTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwks {
        @Bean
        @Primary
        JWKSource<SecurityContext> testJwkSource() {
            return new ImmutableJWKSet<>(new JWKSet(RealtimeGatewayTest.KEY.toPublicJWK()));
        }
    }

    /** Konteks sendiri (bukan cache konteks test) karena test ini mematikannya. */
    private ConfigurableApplicationContext context;
    private int port;

    @org.junit.jupiter.api.BeforeEach
    void start() {
        context = new org.springframework.boot.builder.SpringApplicationBuilder(RealtimeApplication.class, TestJwks.class)
                // Argumen command line: menang atas application.yml (server.port=${SENYAP_RT_PORT:8190}).
                .run("--server.port=0", "--senyap.rt.drain-spread=1200ms", "--senyap.rt.drain-timeout=3s",
                        "--senyap.rt.node-id=node-drain",
                        "--spring.data.redis.host=" + RealtimeGatewayTest.REDIS.getHost(),
                        "--spring.data.redis.port=" + RealtimeGatewayTest.REDIS.getMappedPort(6379));
        port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }

    @org.junit.jupiter.api.AfterEach
    void stop() {
        if (context.isActive()) {
            context.close();
        }
    }

    record Closed(int code, long at) {
    }

    @Test
    void shutdownMenutupSesiDengan1012SecaraBertahapDanMenolakHandshakeBaru() throws Exception {
        var closed = new ConcurrentHashMap<Integer, Closed>();
        var connections = new CopyOnWriteArrayList<Disposable>();
        for (int i = 0; i < 3; i++) {
            int n = i;
            connections.add(open(status -> closed.put(n, new Closed(status.getCode(), System.currentTimeMillis()))));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> presenceFields() == 3);

        var shutdown = new Thread(context::close);
        shutdown.start();
        // Selama drain (sebaran 1,2 dtk) handshake baru ditolak 1012.
        await().atMost(Duration.ofSeconds(3)).until(closed::size, s -> s >= 1);
        var rejected = new CopyOnWriteArrayList<Integer>();
        open(status -> rejected.add(status.getCode()));
        await().atMost(Duration.ofSeconds(5)).until(() -> closed.size() == 3 && !rejected.isEmpty());

        assertThat(closed.values()).extracting(Closed::code).containsOnly(FrameType.CLOSE_SERVICE_RESTART);
        assertThat(rejected).containsExactly(FrameType.CLOSE_SERVICE_RESTART);
        List<Long> times = closed.values().stream().map(Closed::at).sorted().toList();
        assertThat(times.getLast() - times.getFirst()).as("penutupan disebar, tidak serentak").isGreaterThan(700);
        await().atMost(Duration.ofSeconds(10)).until(() -> !shutdown.isAlive());
        assertThat(presenceFields()).as("presence node yang berhenti dibersihkan").isZero();
        connections.forEach(Disposable::dispose);
    }

    private long presenceFields() throws Exception {
        String out = RealtimeGatewayTest.REDIS.execInContainer("redis-cli", "HKEYS", "presence:" + subject()).getStdout();
        return out.lines().filter(l -> l.startsWith("node-drain|")).count();
    }

    private String token;

    private String subject() {
        return subjectOf(token());
    }

    private String token() {
        if (token == null) {
            token = RealtimeGatewayTest.token(RealtimeGatewayTest.KEY, Instant.now().plusSeconds(300));
        }
        return token;
    }

    private static String subjectOf(String jwt) {
        try {
            return com.nimbusds.jwt.SignedJWT.parse(jwt).getJWTClaimsSet().getSubject();
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    private Disposable open(java.util.function.Consumer<CloseStatus> onClose) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token());
        return new ReactorNettyWebSocketClient()
                .execute(URI.create("ws://localhost:" + port + "/v1/ws"), headers, session ->
                        session.receive().then()
                                .and(session.closeStatus().doOnNext(onClose).then(Mono.empty())))
                .subscribe(v -> { }, e -> { });
    }
}
