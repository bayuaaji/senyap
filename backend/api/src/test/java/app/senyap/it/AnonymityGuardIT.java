package app.senyap.it;

import static org.assertj.core.api.Assertions.assertThat;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.web.RequestIdFilter;
import app.senyap.support.IntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Penjaga anonimitas di level HTTP: semua endpoint yang ada bersih, format error benar, dan
 * penjaga benar-benar menangkap kebocoran (dibuktikan dengan controller test yang sengaja bocor).
 */
class AnonymityGuardIT extends IntegrationTest {

    static volatile long leakedUserId;

    @Autowired
    @Qualifier("identityDatabase")
    ModuleDatabase identity;

    @Autowired
    Tsid tsid;

    @BeforeEach
    void userAda() {
        leakedUserId = tsid.next();
        identity.jdbc().sql("""
                        INSERT INTO users (id, phone_hmac, public_id, alias_name, alias_tag, alias_color, age_confirmed_at)
                        VALUES (:id, :phone, :pub, 'Kelinci Jingga', :tag, '#F28C38', :age)""")
                .param("id", leakedUserId)
                .param("phone", UUID.randomUUID().toString().getBytes())
                .param("pub", UUID.randomUUID())
                .param("tag", String.format("%04d", (int) (leakedUserId % 10_000)))
                .param("age", java.sql.Timestamp.from(Instant.now()))
                .update();
    }

    @Test
    void endpointYangAdaTidakMembocorkanIdentitas() {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/v1/tidak-ada").statusCode()).isEqualTo(404);
        // Pemeriksaan sebenarnya ada di IntegrationTest#penjagaAnonimitas (@AfterEach).
    }

    @Test
    void errorMemakaiFormatStandarDenganRequestId() {
        var res = get("/v1/tidak-ada");
        String requestId = res.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).isEqualTo(
                "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Tidak ditemukan.\",\"request_id\":\"" + requestId + "\"}}");
    }

    @Test
    void penjagaMenangkapFieldTerlarang() {
        get("/test/leak/field");
        assertThat(anonymityGuard.violations()).anyMatch(v -> v.contains("field terlarang $.author_id"));
        assertThat(anonymityGuard.violations()).anyMatch(v -> v.contains("field terlarang $.items[0].senderId"));
        anonymityGuard.clear();
    }

    @Test
    void penjagaMenangkapNilaiUserIdDiFieldBerNamaLain() {
        get("/test/leak/value");
        assertThat(anonymityGuard.violations()).anyMatch(v -> v.contains("nilai user ID di $.id"));
        anonymityGuard.clear();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LeakyEndpoints {

        @Bean
        LeakyController leakyController() {
            return new LeakyController();
        }
    }

    @RestController
    static class LeakyController {

        @GetMapping("/test/leak/field")
        Map<String, Object> field() {
            return Map.of("author_id", "1", "items", java.util.List.of(Map.of("senderId", 2)));
        }

        @GetMapping("/test/leak/value")
        Map<String, Object> value() {
            return Map.of("id", Long.toString(leakedUserId));
        }
    }
}
