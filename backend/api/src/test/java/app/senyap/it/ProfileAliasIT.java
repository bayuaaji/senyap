package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;

import app.senyap.identity.IdentityApi;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Alias (functional spec §1.1): suggest → simpan, batas 7 hari, alias_history, IdentityApi batch. */
class ProfileAliasIT extends IntegrationTest {

    @Autowired
    JsonMapper json;

    @Autowired
    IdentityApi identityApi;

    @Autowired
    @Qualifier("identityDatabase")
    ModuleDatabase identity;

    @Test
    void gantiAliasSetelahOnboardingDibatasi7Hari() {
        String token = login();
        put(token, suggest(token, "{}"), 200); // onboarding: bebas
        var tooSoon = send("PUT", "/v1/me/alias",
                "{\"suggestion_token\":\"" + suggest(token, "{\"animal\":\"Rubah\"}").get("suggestion_token").asString() + "\"}",
                token);
        assertThat(tooSoon.statusCode()).isEqualTo(429);
        assertThat(json.readTree(tooSoon.body()).get("error").get("code").asString()).isEqualTo("ALIAS_CHANGE_TOO_SOON");
        long retryAfter = Long.parseLong(tooSoon.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retryAfter).isBetween(7L * 24 * 3600 - 60, 7L * 24 * 3600);

        var profile = json.readTree(send("GET", "/v1/me/profile", null, token).body());
        assertThat(profile.get("alias_changeable_at").isNull()).isFalse();

        // 8 hari kemudian boleh lagi; alias_history mencatat semua alias
        String publicId = profile.get("public_id").asString();
        identity.jdbc().sql("UPDATE users SET alias_changed_at = now() - interval '8 days' WHERE public_id = :p")
                .param("p", UUID.fromString(publicId)).update();
        var s = suggest(token, "{\"animal\":\"Rubah\",\"color\":\"Biru\"}");
        var changed = put(token, s, 200);
        assertThat(changed.get("alias").get("name").asString()).isEqualTo("Rubah Biru");
        long userId = identity.jdbc().sql("SELECT id FROM users WHERE public_id = :p")
                .param("p", UUID.fromString(publicId)).query(Long.class).single();
        assertThat(identity.jdbc().sql("SELECT count(*) FROM alias_history WHERE user_id = :u").param("u", userId)
                .query(Long.class).single()).isEqualTo(3);
        assertThat(identity.jdbc().sql("SELECT count(*) FROM alias_history WHERE user_id = :u AND valid_to IS NULL")
                .param("u", userId).query(Long.class).single()).isEqualTo(1);

        // IdentityApi batch untuk modul lain: user_id → public_id + alias (alias baru langsung terbaca)
        var resolved = identityApi.resolveIdentities(List.of(userId, -1L));
        assertThat(resolved).containsOnlyKeys(userId);
        assertThat(resolved.get(userId).publicId().toString()).isEqualTo(publicId);
        assertThat(resolved.get(userId).name()).isEqualTo("Rubah Biru");
        assertThat(resolved.get(userId).tag()).isEqualTo(changed.get("alias").get("tag").asString());
    }

    @Test
    void suggestionTokenHanyaBerlakuSekaliDanUntukPemiliknya() {
        String alice = login();
        String bob = login();
        var s = suggest(alice, "{}");
        assertThat(send("PUT", "/v1/me/alias", "{\"suggestion_token\":\"" + s.get("suggestion_token").asString() + "\"}", bob)
                .statusCode()).isEqualTo(400);
        // Sudah dikonsumsi oleh percobaan di atas
        assertThat(send("PUT", "/v1/me/alias", "{\"suggestion_token\":\"" + s.get("suggestion_token").asString() + "\"}", alice)
                .statusCode()).isEqualTo(400);
    }

    @Test
    void hanyaHewanDanWarnaDariDaftar() {
        String token = login();
        assertThat(send("POST", "/v1/me/alias/suggest", "{\"animal\":\"Budi Santoso\"}", token).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/me/alias/suggest", "{\"color\":\"Pelangi\"}", token).statusCode()).isEqualTo(400);

        var catalog = json.readTree(send("GET", "/v1/aliases/catalog", null, token).body());
        assertThat(catalog.get("animals").size()).isGreaterThanOrEqualTo(200);
        assertThat(catalog.get("colors").size()).isEqualTo(20);
        assertThat(catalog.get("colors").get(0).has("hex")).isTrue();
    }

    private String login() {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var res = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body()).get("tokens").get("access_token").asString();
    }

    private JsonNode suggest(String token, String body) {
        var res = send("POST", "/v1/me/alias/suggest", body, token);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private JsonNode put(String token, JsonNode suggestion, int status) {
        var res = send("PUT", "/v1/me/alias",
                "{\"suggestion_token\":\"" + suggestion.get("suggestion_token").asString() + "\"}", token);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return json.readTree(res.body());
    }
}
