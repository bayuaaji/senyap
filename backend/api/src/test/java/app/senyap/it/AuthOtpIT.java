package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.ratelimit.RateLimiter;
import app.senyap.support.IntegrationTest;
import com.nimbusds.jose.jwk.JWKSet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.json.JsonMapper;

/** OTP + rate limit (functional spec FS-01, §6) dan JWKS publik (M12). */
class AuthOtpIT extends IntegrationTest {

    @Autowired
    JsonMapper json;

    @Autowired
    RateLimiter rateLimiter;

    @Autowired
    @Qualifier("authDatabase")
    ModuleDatabase auth;

    @Test
    void requestOtpDibatasi3PerJamPerNomor() {
        String phone = randomLocalPhone();
        for (int i = 0; i < 3; i++) {
            assertThat(send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null).statusCode()).isEqualTo(202);
        }
        var limited = send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 3600L);
    }

    @Test
    void rateLimitPerIpMemakaiPenghitungYangSama() {
        String key = "test:ip:" + UUID.randomUUID();
        rateLimiter.hit(key, 2, Duration.ofMinutes(1));
        rateLimiter.hit(key, 2, Duration.ofMinutes(1));
        assertThatThrownBy(() -> rateLimiter.hit(key, 2, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("RATE_LIMITED");
                    assertThat(e.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void kodeSalahMaksimal3KaliLaluKodeHangus() {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        for (int i = 0; i < 3; i++) {
            var wrong = send("POST", "/v1/auth/verify", verifyBody(phone, "123456", "ios", null), null);
            assertThat(wrong.statusCode()).isEqualTo(400);
            assertThat(code(wrong)).isEqualTo("OTP_INVALID");
        }
        var exhausted = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null);
        assertThat(exhausted.statusCode()).isEqualTo(400);
        assertThat(code(exhausted)).isEqualTo("OTP_INVALID");

        // Kode baru menggantikan yang lama
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        assertThat(send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null).statusCode()).isEqualTo(200);
        // Kode yang sudah dipakai tidak bisa dipakai lagi
        assertThat(send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null).statusCode()).isEqualTo(400);
    }

    @Test
    void kodeKedaluwarsaSetelah5Menit() {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        auth.jdbc().sql("UPDATE otp_attempts SET expires_at = now() - interval '1 second' WHERE verified_at IS NULL "
                + "AND created_at > now() - interval '1 minute'").update();
        var expired = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null);
        assertThat(expired.statusCode()).isEqualTo(400);
        assertThat(code(expired)).isEqualTo("OTP_INVALID");
    }

    @Test
    void inputTidakValidDitolakTanpaMenyebutNomor() {
        var badPhone = send("POST", "/v1/auth/otp", "{\"phone\":\"12ab\"}", null);
        assertThat(badPhone.statusCode()).isEqualTo(400);
        assertThat(code(badPhone)).isEqualTo("VALIDATION_FAILED");

        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var noAge = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null)
                .replace("\"age_confirmed\":true", "\"age_confirmed\":false"), null);
        assertThat(noAge.statusCode()).isEqualTo(400);
        assertThat(noAge.body()).doesNotContain(phone.substring(1));
        var badPlatform = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "windows", null), null);
        assertThat(badPlatform.statusCode()).isEqualTo(400);
        // Percobaan yang ditolak validasi tidak memakan jatah kode
        assertThat(send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null).statusCode()).isEqualTo(200);
    }

    @Test
    void jwksPublikHanyaBerisiPublicKey() throws Exception {
        var res = get("/.well-known/jwks.json");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Cache-Control").orElse("")).contains("max-age=300");
        var set = JWKSet.parse(res.body());
        assertThat(set.getKeys()).hasSize(1);
        var key = set.getKeys().getFirst();
        assertThat(key.isPrivate()).isFalse();
        assertThat(key.getKeyID()).isNotBlank();
        assertThat(key.getKeyType().getValue()).isEqualTo("EC");
    }

    private String code(java.net.http.HttpResponse<String> res) {
        return json.readTree(res.body()).get("error").get("code").asString();
    }
}
