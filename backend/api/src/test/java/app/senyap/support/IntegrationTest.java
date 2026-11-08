package app.senyap.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Basis semua integration test: Postgres 16, Redis 7, Redpanda, MinIO lewat Testcontainers (satu set
 * container untuk seluruh JVM), aplikasi penuh dengan profil {@code test,api,worker}, dan penjaga
 * anonimitas yang memeriksa setiap respons JSON.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "api", "worker"})
@Import(AnonymityGuardConfig.class)
public abstract class IntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("senyap").withUsername("senyap_admin").withPassword("senyap_admin");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    protected static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v24.3.18");

    /** Image resmi minio/minio tidak lagi tersedia publik; Chainguard = build MinIO yang sama. */
    protected static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"))
            .withUserName("senyap").withPassword("senyap-minio");

    static {
        Startables.deepStart(POSTGRES, REDIS, REDPANDA, MINIO).join();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry r) {
        r.add("senyap.db.bootstrap.url", POSTGRES::getJdbcUrl);
        for (String m : new String[] {"auth", "identity", "media", "posts", "chat", "notifications", "safety"}) {
            r.add("senyap." + m + ".datasource.url", POSTGRES::getJdbcUrl);
        }
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        r.add("senyap.media.s3.endpoint", MINIO::getS3URL);
        r.add("senyap.media.public-base-url", () -> MINIO.getS3URL() + "/media");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected AnonymityGuardFilter anonymityGuard;

    private final HttpClient http = HttpClient.newHttpClient();

    protected HttpResponse<String> get(String path) {
        return send("GET", path, null, null);
    }

    /**
     * Request JSON. {@code body} null = tanpa body; {@code accessToken} null = tanpa header Authorization.
     */
    protected HttpResponse<String> send(String method, String path, String body, String accessToken) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void penjagaAnonimitas() {
        var violations = anonymityGuard.violations();
        anonymityGuard.clear();
        assertThat(violations).as("respons membocorkan identitas internal").isEmpty();
    }
}
