package app.senyap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Daftar/masuk dengan email + kata sandi (FS-01, revisi 9 Nov 2026): register, login, lupa + reset kata sandi,
 * kasus gagal, hapus akun lalu login lagi (dipulihkan), dan purge (kredensial email ikut dihapus). Email tidak
 * boleh ada di respons (penjaga anonimitas) maupun log.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailAuthIT extends IntegrationTest {

    static final String DEVICE = "{\"platform\":\"ios\",\"push_token\":null}";

    @Autowired
    JsonMapper json;

    @Autowired
    @Qualifier("identityDatabase")
    ModuleDatabase identity;

    @Autowired
    @Qualifier("authDatabase")
    ModuleDatabase auth;

    @Test
    void daftarLaluMasukDenganEmail(CapturedOutput output) {
        String email = randomEmail();
        var reg = body(register("  " + email.toUpperCase() + " ", "rahasia-123", true), 201);
        assertThat(reg.get("is_new").asBoolean()).isTrue();
        assertThat(reg.get("tokens").get("access_token").asString()).isNotBlank();
        assertThat(reg.get("tokens").get("refresh_token").asString()).isNotBlank();
        assertThat(reg.get("tokens").get("expires_in").asLong()).isEqualTo(900);
        String access = reg.get("tokens").get("access_token").asString();
        var profile = body(send("GET", "/v1/me/profile", null, access), 200);
        String publicId = profile.get("public_id").asString();
        assertThat(profile.get("gender").asString()).isEqualTo("unspecified");
        assertThat(profile.get("onboarded").asBoolean()).isFalse();

        // Akun tanpa nomor HP; email tersimpan ternormalisasi di schema auth.
        assertThat(identity.jdbc().sql("SELECT phone_hmac IS NULL FROM users WHERE public_id = :p")
                .param("p", UUID.fromString(publicId)).query(Boolean.class).single()).isTrue();
        assertThat(auth.jdbc().sql("SELECT count(*) FROM email_credentials WHERE email = :e").param("e", email)
                .query(Long.class).single()).isEqualTo(1);

        // Login (huruf besar/spasi dinormalisasi) → bentuk respons sama dengan /v1/auth/verify
        var login = body(login(" " + email.toUpperCase(), "rahasia-123"), 200);
        assertThat(login.get("is_new").asBoolean()).isTrue();
        assertThat(login.has("tokens")).isTrue();
        String access2 = login.get("tokens").get("access_token").asString();
        assertThat(body(send("GET", "/v1/me/profile", null, access2), 200).get("public_id").asString()).isEqualTo(publicId);

        // Email yang sama tidak bisa didaftarkan lagi
        assertThat(error(register(email, "rahasia-456", true), 409)).isEqualTo("EMAIL_TAKEN");
        assertThat(output.getAll()).doesNotContain(email).doesNotContain("rahasia-123");
    }

    @Test
    void validasiDaftar() {
        assertThat(error(register(randomEmail(), "pendek", true), 400)).isEqualTo("WEAK_PASSWORD");
        assertThat(error(register(randomEmail(), "x".repeat(73), true), 400)).isEqualTo("WEAK_PASSWORD");
        assertThat(error(register("bukan-email", "rahasia-123", true), 400)).isEqualTo("INVALID_EMAIL");
        assertThat(error(register("a@b", "rahasia-123", true), 400)).isEqualTo("INVALID_EMAIL");
        assertThat(error(register(randomEmail(), "rahasia-123", false), 400)).isEqualTo("VALIDATION_FAILED");
        var noDevice = send("POST", "/v1/auth/email/register",
                "{\"email\":\"" + randomEmail() + "\",\"password\":\"rahasia-123\",\"age_confirmed\":true}", null);
        assertThat(error(noDevice, 400)).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void loginGagalSelaluGenerik() {
        String email = randomEmail();
        body(register(email, "rahasia-123", true), 201);
        var wrong = send("POST", "/v1/auth/email/login", loginBody(email, "salah-sandi"), null);
        var unknown = send("POST", "/v1/auth/email/login", loginBody(randomEmail(), "salah-sandi"), null);
        var malformed = send("POST", "/v1/auth/email/login", loginBody("bukan-email", "salah-sandi"), null);
        for (var res : new HttpResponse<?>[] {wrong, unknown, malformed}) {
            assertThat(res.statusCode()).isEqualTo(401);
        }
        var w = json.readTree(wrong.body()).get("error");
        var u = json.readTree(unknown.body()).get("error");
        assertThat(w.get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(u.get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(w.get("message").asString()).isEqualTo(u.get("message").asString());
    }

    @Test
    void rateLimitLoginPerEmail() {
        String email = randomEmail();
        body(register(email, "rahasia-123", true), 201);
        for (int i = 0; i < 10; i++) {
            assertThat(login(email, "salah-sandi").statusCode()).isEqualTo(401);
        }
        var limited = login(email, "rahasia-123");
        assertThat(error(limited, 429)).isEqualTo("RATE_LIMITED");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void lupaDanResetKataSandiMencabutSemuaSesi(CapturedOutput output) {
        String email = randomEmail();
        var reg = body(register(email, "rahasia-lama", true), 201);
        String refresh = reg.get("tokens").get("refresh_token").asString();

        // Selalu 202, terdaftar atau tidak (tidak membocorkan email terdaftar)
        assertThat(forgot(randomEmail()).statusCode()).isEqualTo(202);
        assertThat(forgot("bukan-email").statusCode()).isEqualTo(202);
        assertThat(forgot(email.toUpperCase()).statusCode()).isEqualTo(202);
        assertThat(output.getAll()).contains("Email reset kata sandi mock").doesNotContain(email);

        assertThat(error(reset(email, "123456", "rahasia-baru"), 400)).isEqualTo("OTP_INVALID");
        assertThat(error(reset(email, "000000", "pendek"), 400)).isEqualTo("WEAK_PASSWORD");
        assertThat(error(reset(randomEmail(), "000000", "rahasia-baru"), 400)).isEqualTo("OTP_INVALID");
        assertThat(reset(email, "000000", "rahasia-baru").statusCode()).isEqualTo(204);
        // Kode hanya sekali pakai
        assertThat(error(reset(email, "000000", "rahasia-lain"), 400)).isEqualTo("OTP_INVALID");

        // Sesi lama dicabut; kata sandi lama tidak berlaku, yang baru berlaku
        assertThat(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + refresh + "\"}", null).statusCode())
                .isEqualTo(401);
        assertThat(error(login(email, "rahasia-lama"), 401)).isEqualTo("INVALID_CREDENTIALS");
        assertThat(login(email, "rahasia-baru").statusCode()).isEqualTo(200);
    }

    @Test
    void kodeResetKedaluwarsaDanPercobaanTerbatas() {
        String email = randomEmail();
        body(register(email, "rahasia-123", true), 201);
        assertThat(error(reset(email, "000000", "rahasia-baru"), 400)).as("belum minta kode").isEqualTo("OTP_INVALID");
        forgot(email);
        auth.jdbc().sql("""
                        UPDATE password_reset_codes SET expires_at = now() - interval '1 minute'
                        WHERE user_id = (SELECT user_id FROM email_credentials WHERE email = :e)""")
                .param("e", email).update();
        assertThat(error(reset(email, "000000", "rahasia-baru"), 400)).isEqualTo("OTP_INVALID");

        forgot(email);
        for (int i = 0; i < 5; i++) {
            assertThat(error(reset(email, "111111", "rahasia-baru"), 400)).isEqualTo("OTP_INVALID");
        }
        assertThat(error(reset(email, "000000", "rahasia-baru"), 400)).as("percobaan habis").isEqualTo("OTP_INVALID");
    }

    @Test
    void akunEmailYangDihapusDipulihkanSaatLoginLaluDiPurge() {
        String email = randomEmail();
        var reg = body(register(email, "rahasia-123", true), 201);
        String access = reg.get("tokens").get("access_token").asString();
        String publicId = body(send("GET", "/v1/me/profile", null, access), 200).get("public_id").asString();

        assertThat(send("DELETE", "/v1/me", "{\"confirm\":\"HAPUS\"}", access).statusCode()).isEqualTo(202);
        assertThat(userColumn(publicId, "status")).isEqualTo("DELETED");
        assertThat(send("GET", "/v1/me/profile", null, access).statusCode()).isEqualTo(401);

        // Login lagi sebelum purge membatalkan penghapusan: public_id + alias tetap
        var again = body(login(email, "rahasia-123"), 200);
        String access2 = again.get("tokens").get("access_token").asString();
        assertThat(body(send("GET", "/v1/me/profile", null, access2), 200).get("public_id").asString()).isEqualTo(publicId);
        assertThat(userColumn(publicId, "status")).isEqualTo("ACTIVE");
        assertThat(userColumn(publicId, "deleted_at")).isNull();
        long userId = Long.parseLong(userColumn(publicId, "id"));
        assertThat(identity.jdbc().sql("SELECT count(*) FROM outbox WHERE event_type = 'user.restored' AND event_key = :k")
                .param("k", Long.toString(userId)).query(Long.class).single()).isEqualTo(1);

        // Hapus lagi + masa tenggang lewat → purge → consumer auth menghapus kredensial email
        assertThat(send("DELETE", "/v1/me", "{\"confirm\":\"HAPUS\"}", access2).statusCode()).isEqualTo(202);
        identity.jdbc().sql("UPDATE users SET deleted_at = now() - interval '30 days' WHERE public_id = :p")
                .param("p", UUID.fromString(publicId)).update();
        await().atMost(Duration.ofSeconds(30)).until(() -> auth.jdbc()
                .sql("SELECT count(*) FROM email_credentials WHERE user_id = :u").param("u", userId)
                .query(Long.class).single() == 0);
        assertThat(userColumn(publicId, "phone_hmac")).isNull();

        // Email lama tidak lagi terhubung: login gagal, daftar lagi = akun baru
        assertThat(error(login(email, "rahasia-123"), 401)).isEqualTo("INVALID_CREDENTIALS");
        var fresh = body(register(email, "rahasia-123", true), 201);
        String newPublicId = body(send("GET", "/v1/me/profile", null, fresh.get("tokens").get("access_token").asString()),
                200).get("public_id").asString();
        assertThat(newPublicId).isNotEqualTo(publicId);
    }

    @Test
    void akunBannedTidakBisaMasuk() {
        String email = randomEmail();
        var reg = body(register(email, "rahasia-123", true), 201);
        String publicId = body(send("GET", "/v1/me/profile", null, reg.get("tokens").get("access_token").asString()), 200)
                .get("public_id").asString();
        identity.jdbc().sql("UPDATE users SET status = 'BANNED' WHERE public_id = :p")
                .param("p", UUID.fromString(publicId)).update();
        assertThat(error(login(email, "rahasia-123"), 403)).isEqualTo("FORBIDDEN");
    }

    private static String randomEmail() {
        return "uji" + ThreadLocalRandom.current().nextLong(1_000_000_000L) + "@contoh.id";
    }

    private HttpResponse<String> register(String email, String password, boolean ageConfirmed) {
        return send("POST", "/v1/auth/email/register", """
                {"email":"%s","password":"%s","device":%s,"age_confirmed":%s}"""
                .formatted(email, password, DEVICE, ageConfirmed), null);
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s","device":%s}""".formatted(email, password, DEVICE);
    }

    private HttpResponse<String> login(String email, String password) {
        return send("POST", "/v1/auth/email/login", loginBody(email, password), null);
    }

    private HttpResponse<String> forgot(String email) {
        return send("POST", "/v1/auth/email/forgot", "{\"email\":\"" + email + "\"}", null);
    }

    private HttpResponse<String> reset(String email, String code, String newPassword) {
        return send("POST", "/v1/auth/email/reset", """
                {"email":"%s","code":"%s","new_password":"%s"}""".formatted(email, code, newPassword), null);
    }

    private String userColumn(String publicId, String column) {
        return identity.jdbc().sql("SELECT " + column + "::text FROM users WHERE public_id = :p")
                .param("p", UUID.fromString(publicId)).query((rs, n) -> rs.getString(1)).list().getFirst();
    }

    private JsonNode body(HttpResponse<String> res, int expectedStatus) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(expectedStatus);
        return json.readTree(res.body());
    }

    private String error(HttpResponse<String> res, int expectedStatus) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(expectedStatus);
        return json.readTree(res.body()).get("error").get("code").asString();
    }
}
