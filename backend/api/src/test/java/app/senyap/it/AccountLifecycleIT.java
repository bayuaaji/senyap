package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.toE164;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kriteria selesai Fase 1 (build guide §4): register → login di device lain → refresh → hapus akun,
 * lewat HTTP penuh. Penjaga anonimitas memeriksa setiap respons; nomor HP tidak boleh muncul di DB atau log.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccountLifecycleIT extends IntegrationTest {

    @Autowired
    JsonMapper json;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    @Autowired
    @Qualifier("identityDatabase")
    ModuleDatabase identity;

    @Autowired
    @Qualifier("authDatabase")
    ModuleDatabase auth;

    @Test
    void registerLoginDiDeviceLainRefreshLaluHapusAkun(CapturedOutput output) {
        String phone = randomLocalPhone();

        // 1. Register di device A (iOS)
        assertThat(send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null).statusCode()).isEqualTo(202);
        var verifyA = body(send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", "push-a-" + phone), null), 200);
        assertThat(verifyA.get("is_new").asBoolean()).isTrue();
        String accessA = verifyA.get("tokens").get("access_token").asString();
        String refreshA = verifyA.get("tokens").get("refresh_token").asString();
        assertThat(verifyA.get("tokens").get("expires_in").asLong()).isEqualTo(900);

        var profile = body(send("GET", "/v1/me/profile", null, accessA), 200);
        String publicId = profile.get("public_id").asString();
        assertThat(profile.get("alias").get("name").asString()).matches("\\S.* \\S+");
        assertThat(profile.get("alias").get("tag").asString()).matches("[0-9]{4}");
        assertThat(profile.get("alias").get("color").asString()).matches("#[0-9A-F]{6}");
        assertThat(profile.get("onboarded").asBoolean()).isFalse();
        assertThat(profile.get("alias_changeable_at").isNull()).isTrue();

        // Buat profil: "Acak lagi" lalu "Pakai alias ini" (batas 7 hari tidak berlaku saat onboarding)
        body(send("POST", "/v1/me/alias/suggest", null, accessA), 200);
        var suggestion = body(send("POST", "/v1/me/alias/suggest", "{\"animal\":\"Kelinci\",\"color\":\"Jingga\"}", accessA), 200);
        assertThat(suggestion.get("name").asString()).isEqualTo("Kelinci Jingga");
        assertThat(suggestion.get("color").asString()).isEqualTo("#F28C38");
        var chosen = body(send("PUT", "/v1/me/alias",
                "{\"suggestion_token\":\"" + suggestion.get("suggestion_token").asString() + "\"}", accessA), 200);
        assertThat(chosen.get("alias").get("name").asString()).isEqualTo("Kelinci Jingga");
        assertThat(chosen.get("onboarded").asBoolean()).isTrue();
        assertThat(chosen.get("alias_changeable_at").asString()).endsWith(":00Z");
        assertThat(chosen.get("public_id").asString()).isEqualTo(publicId);

        // 2. Login di device B (Android) dengan nomor yang sama: akun lama, alias lama
        assertThat(send("POST", "/v1/auth/otp", "{\"phone\":\"" + toE164(phone) + "\",\"channel\":\"whatsapp\"}", null)
                .statusCode()).isEqualTo(202);
        var verifyB = body(send("POST", "/v1/auth/verify", verifyBody(toE164(phone), MOCK_CODE, "android", null), null), 200);
        assertThat(verifyB.get("is_new").asBoolean()).isFalse();
        String accessB = verifyB.get("tokens").get("access_token").asString();
        var profileB = body(send("GET", "/v1/me/profile", null, accessB), 200);
        assertThat(profileB.get("public_id").asString()).isEqualTo(publicId);
        assertThat(profileB.get("alias").get("name").asString()).isEqualTo("Kelinci Jingga");
        assertThat(deviceCount(publicId)).isEqualTo(2);

        // 3. Refresh di device A: token dirotasi, token lama tidak berlaku lagi (di luar jeda retry)
        var refreshed = body(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + refreshA + "\"}", null), 200);
        String accessA2 = refreshed.get("tokens").get("access_token").asString();
        String refreshA2 = refreshed.get("tokens").get("refresh_token").asString();
        assertThat(refreshA2).isNotEqualTo(refreshA);
        assertThat(send("GET", "/v1/me/profile", null, accessA2).statusCode()).isEqualTo(200);
        var refreshedAgain = body(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + refreshA2 + "\"}", null), 200);
        String refreshA3 = refreshedAgain.get("tokens").get("refresh_token").asString();

        // Ganti push token device B
        assertThat(send("PUT", "/v1/me/device", "{\"push_token\":\"push-b-" + phone + "\"}", accessB).statusCode())
                .isEqualTo(204);

        // 4. Hapus akun dari device B
        assertThat(send("DELETE", "/v1/me", "{\"confirm\":\"hapus\"}", accessB).statusCode()).isEqualTo(400);
        assertThat(send("DELETE", "/v1/me", "{\"confirm\":\"HAPUS\"}", accessB).statusCode()).isEqualTo(202);

        // Semua sesi tercabut: access token ditolak (status DELETED), refresh token device A tidak berlaku
        assertThat(send("GET", "/v1/me/profile", null, accessA2).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/v1/me/profile", null, accessB).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + refreshA3 + "\"}", null).statusCode())
                .isEqualTo(401);
        assertThat(deviceCount(publicId)).isZero();
        assertThat(outboxTypes()).contains("user.registered", "user.alias_changed", "user.deleted");

        // Selama masa tenggang nomor yang sama tidak bisa dipakai
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var blocked = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null);
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(errorCode(blocked)).isEqualTo("FORBIDDEN");

        // 5. Masa tenggang lewat → job purge worker memutus nomor HP dari akun
        identity.jdbc().sql("UPDATE users SET deleted_at = now() - interval '30 days' WHERE public_id = :p")
                .param("p", java.util.UUID.fromString(publicId)).update();
        await().atMost(Duration.ofSeconds(15)).until(() -> identity.jdbc()
                .sql("SELECT purged_at IS NOT NULL FROM users WHERE public_id = :p")
                .param("p", java.util.UUID.fromString(publicId)).query(Boolean.class).single());
        assertThat(outboxTypes()).contains("user.purged");

        // Nomor yang sama sekarang mendaftar sebagai akun baru (kuota OTP 3/jam nomor ini sudah habis dipakai di atas)
        redis.delete(redis.keys("rl:auth:otp:phone:*"));
        assertThat(send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null).statusCode()).isEqualTo(202);
        var again = body(send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null), 200);
        assertThat(again.get("is_new").asBoolean()).isTrue();
        var newProfile = body(send("GET", "/v1/me/profile", null, again.get("tokens").get("access_token").asString()), 200);
        assertThat(newProfile.get("public_id").asString()).isNotEqualTo(publicId);

        // Nomor HP (lokal maupun E.164) tidak pernah tersimpan atau tercatat di log
        assertThat(output.getAll()).doesNotContain(phone.substring(1)).doesNotContain("push-a-");
    }

    @Test
    void tokenRefreshYangDipakaiUlangMencabutSeluruhFamily() throws Exception {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var v = body(send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "android", null), null), 200);
        String r1 = v.get("tokens").get("refresh_token").asString();

        String r2 = body(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + r1 + "\"}", null), 200)
                .get("tokens").get("refresh_token").asString();
        // Retry r1 dalam jeda (balasan sebelumnya hilang): dapat token baru, r2 dicabut
        String r3 = body(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + r1 + "\"}", null), 200)
                .get("tokens").get("refresh_token").asString();
        assertThat(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + r2 + "\"}", null).statusCode()).isEqualTo(401);
        // r2 dipakai ulang di luar aturan → seluruh family dicabut, termasuk r3
        assertThat(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + r3 + "\"}", null).statusCode()).isEqualTo(401);

        // Lewat jeda: token lama yang dipakai lagi = pencurian
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        String s1 = body(send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "android", null), null), 200)
                .get("tokens").get("refresh_token").asString();
        String s2 = body(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + s1 + "\"}", null), 200)
                .get("tokens").get("refresh_token").asString();
        auth.jdbc().sql("UPDATE refresh_tokens SET revoked_at = revoked_at - interval '1 minute' WHERE revoked_at IS NOT NULL")
                .update();
        assertThat(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + s1 + "\"}", null).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + s2 + "\"}", null).statusCode()).isEqualTo(401);
    }

    @Test
    void logoutMencabutSesiDeviceItuSaja() {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var a = body(send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null), 200);
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var b = body(send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "android", null), null), 200);

        assertThat(send("POST", "/v1/auth/logout", null, a.get("tokens").get("access_token").asString()).statusCode())
                .isEqualTo(204);
        assertThat(send("POST", "/v1/auth/refresh",
                "{\"refresh_token\":\"" + a.get("tokens").get("refresh_token").asString() + "\"}", null).statusCode())
                .isEqualTo(401);
        assertThat(send("POST", "/v1/auth/refresh",
                "{\"refresh_token\":\"" + b.get("tokens").get("refresh_token").asString() + "\"}", null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void tanpaTokenAtauTokenPalsuDitolak() {
        var res = send("GET", "/v1/me/profile", null, null);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(errorCode(res)).isEqualTo("UNAUTHENTICATED");
        assertThat(send("GET", "/v1/me/profile", null, "eyJhbGciOiJub25lIn0.e30.").statusCode()).isEqualTo(401);
        assertThat(send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"tidak-ada\"}", null).statusCode()).isEqualTo(401);
    }

    private long deviceCount(String publicId) {
        long userId = identity.jdbc().sql("SELECT id FROM users WHERE public_id = :p")
                .param("p", java.util.UUID.fromString(publicId)).query(Long.class).single();
        return auth.jdbc().sql("SELECT count(*) FROM devices WHERE user_id = :u").param("u", userId)
                .query(Long.class).single();
    }

    private java.util.List<String> outboxTypes() {
        return identity.jdbc().sql("SELECT event_type FROM outbox").query(String.class).list();
    }

    private JsonNode body(java.net.http.HttpResponse<String> res, int expectedStatus) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(expectedStatus);
        return json.readTree(res.body());
    }

    private String errorCode(java.net.http.HttpResponse<String> res) {
        return json.readTree(res.body()).get("error").get("code").asString();
    }

}
