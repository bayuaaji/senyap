package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;

import app.senyap.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Revisi 9 Nov 2026: gender + avatar di profil, katalog alias per gender, dan gender + avatar ikut tampil di
 * author post/balasan serta lawan bicara Bisik.
 */
class ProfileGenderAvatarIT extends IntegrationTest {

    @Autowired
    JsonMapper json;

    record User(String token, String publicId) {
    }

    @Test
    void profilBawaanLaluGantiGenderDanAvatarTanpaMengubahAlias() {
        var u = login();
        var before = getJson("/v1/me/profile", u.token());
        assertThat(before.get("gender").asString()).isEqualTo("unspecified");
        assertThat(before.get("avatar").isNull()).isTrue();

        var updated = put(u, "{\"gender\":\"female\",\"avatar\":\"rubah\"}", 200);
        assertThat(updated.get("gender").asString()).isEqualTo("female");
        assertThat(updated.get("avatar").get("id").asString()).isEqualTo("rubah");
        assertThat(updated.get("avatar").get("emoji").asString()).isEqualTo("🦊");
        assertThat(updated.get("alias")).isEqualTo(before.get("alias"));

        // Field yang tidak dikirim tidak berubah; gender boleh diganti lagi kapan saja.
        var male = put(u, "{\"gender\":\"male\"}", 200);
        assertThat(male.get("gender").asString()).isEqualTo("male");
        assertThat(male.get("avatar").get("id").asString()).isEqualTo("rubah");
        // avatar: null = hapus
        var cleared = put(u, "{\"avatar\":null}", 200);
        assertThat(cleared.get("avatar").isNull()).isTrue();
        assertThat(cleared.get("gender").asString()).isEqualTo("male");
        assertThat(getJson("/v1/me/profile", u.token()).get("alias")).isEqualTo(before.get("alias"));
    }

    @Test
    void validasiGenderDanAvatar() {
        var u = login();
        assertThat(error(send("PUT", "/v1/me/profile", "{\"gender\":\"lainnya\"}", u.token()))).isEqualTo("VALIDATION_FAILED");
        assertThat(error(send("PUT", "/v1/me/profile", "{\"avatar\":\"foto-asli\"}", u.token()))).isEqualTo("VALIDATION_FAILED");
        assertThat(error(send("PUT", "/v1/me/profile", "{\"avatar\":5}", u.token()))).isEqualTo("VALIDATION_FAILED");
        assertThat(send("PUT", "/v1/me/profile", "{}", u.token()).statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/v1/me/profile", "{\"gender\":\"male\"}", null).statusCode()).isEqualTo(401);
    }

    @Test
    void katalogPerGenderDanDefaultGenderUser() {
        var u = login();
        var male = getJson("/v1/aliases/catalog?gender=male", u.token());
        assertThat(male.get("gender").asString()).isEqualTo("male");
        assertThat(strings(male.get("first"))).contains("Pangeran", "Ksatria", "Elang").doesNotContain("Putri");
        assertThat(male.get("second").get(0).has("name")).isTrue();
        assertThat(male.get("second").get(0).get("hex").asString()).matches("#[0-9A-F]{6}");
        assertThat(male.get("avatars").size()).isGreaterThanOrEqualTo(20);
        assertThat(male.get("avatars").get(0).has("id")).isTrue();
        assertThat(male.get("avatars").get(0).has("emoji")).isTrue();

        var female = getJson("/v1/aliases/catalog?gender=female", u.token());
        assertThat(strings(female.get("first"))).contains("Putri", "Dewi", "Mawar").doesNotContain("Pangeran");

        assertThat(getJson("/v1/aliases/catalog", u.token()).get("gender").asString()).isEqualTo("unspecified");
        put(u, "{\"gender\":\"female\"}", 200);
        assertThat(getJson("/v1/aliases/catalog", u.token()).get("gender").asString()).isEqualTo("female");
        assertThat(error(send("GET", "/v1/aliases/catalog?gender=x", null, u.token()))).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void suggestMemakaiKatalogGenderUser() {
        var u = login();
        put(u, "{\"gender\":\"male\"}", 200);
        var maleFirst = strings(getJson("/v1/aliases/catalog?gender=male", u.token()).get("first"));
        for (int i = 0; i < 5; i++) {
            var s = suggest(u, "{}", 200);
            assertThat(maleFirst).contains(s.get("name").asString().split(" ")[0]);
        }
        var chosen = suggest(u, "{\"first\":\"Pangeran\",\"second\":\"Senja\"}", 200);
        assertThat(chosen.get("name").asString()).isEqualTo("Pangeran Senja");
        assertThat(chosen.get("color").asString()).isEqualTo("#E07A3F");
        // Nama field lama (animal/color) tetap diterima.
        assertThat(suggest(u, "{\"animal\":\"Elang\",\"color\":\"Utara\"}", 200).get("name").asString())
                .isEqualTo("Elang Utara");
        // Kata dari katalog gender lain ditolak.
        assertThat(error(send("POST", "/v1/me/alias/suggest", "{\"first\":\"Putri\"}", u.token())))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(error(send("POST", "/v1/me/alias/suggest", "{\"second\":\"Jelita\"}", u.token())))
                .isEqualTo("VALIDATION_FAILED");

        var saved = send("PUT", "/v1/me/alias",
                "{\"suggestion_token\":\"" + chosen.get("suggestion_token").asString() + "\"}", u.token());
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(json.readTree(saved.body()).get("alias").get("name").asString()).isEqualTo("Pangeran Senja");

        // Ganti gender tidak mengubah alias tersimpan.
        assertThat(put(u, "{\"gender\":\"female\"}", 200).get("alias").get("name").asString()).isEqualTo("Pangeran Senja");
        var female = suggest(u, "{\"first\":\"Putri\",\"second\":\"Salju\"}", 200);
        assertThat(female.get("name").asString()).isEqualTo("Putri Salju");
    }

    @Test
    void genderDanAvatarTampilDiPostBalasanDanBisik() {
        var a = login();
        var b = login();
        put(a, "{\"gender\":\"female\",\"avatar\":\"kupu-kupu\"}", 200);
        String postId = createPost(a.token(), "Hari ini aku akhirnya berani bilang tidak.");

        var post = getJson("/v1/posts/" + postId, b.token());
        var author = post.get("author");
        assertThat(author.get("public_id").asString()).isEqualTo(a.publicId());
        assertThat(author.get("gender").asString()).isEqualTo("female");
        assertThat(author.get("avatar").asString()).isEqualTo("🦋");
        assertThat(author.has("color")).isTrue();

        var reply = send("POST", "/v1/posts/" + postId + "/replies", "{\"body\":\"Bangga sama kamu.\"}", b.token());
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        var replyAuthor = json.readTree(reply.body()).get("author");
        assertThat(replyAuthor.get("gender").asString()).isEqualTo("unspecified");
        assertThat(replyAuthor.get("avatar").isNull()).isTrue();

        // Bisik: lawan bicara membawa gender + avatar terkini (dibaca saat render).
        var conv = send("POST", "/v1/conversations", """
                {"origin_type":"POST","origin_id":"%s","first_message":"Halo","client_msg_id":"%s"}"""
                .formatted(postId, UUID.randomUUID()), b.token());
        assertThat(conv.statusCode()).as(conv.body()).isEqualTo(201);
        var peer = json.readTree(conv.body()).get("peer");
        assertThat(peer.get("gender").asString()).isEqualTo("female");
        assertThat(peer.get("avatar").asString()).isEqualTo("🦋");

        put(b, "{\"gender\":\"male\",\"avatar\":\"singa\"}", 200);
        var requests = getJson("/v1/conversations?box=requests", a.token()).get("items");
        assertThat(requests.size()).isEqualTo(1);
        assertThat(requests.get(0).get("peer").get("public_id").asString()).isEqualTo(b.publicId());
        assertThat(requests.get(0).get("peer").get("gender").asString()).isEqualTo("male");
        assertThat(requests.get(0).get("peer").get("avatar").asString()).isEqualTo("🦁");
        var detail = getJson("/v1/conversations/" + requests.get(0).get("id").asString(), a.token());
        assertThat(detail.get("peer").get("avatar").asString()).isEqualTo("🦁");
    }

    private User login() {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var res = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        String token = json.readTree(res.body()).get("tokens").get("access_token").asString();
        return new User(token, getJson("/v1/me/profile", token).get("public_id").asString());
    }

    private JsonNode put(User u, String body, int status) {
        var res = send("PUT", "/v1/me/profile", body, u.token());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return json.readTree(res.body());
    }

    private JsonNode suggest(User u, String body, int status) {
        var res = send("POST", "/v1/me/alias/suggest", body, u.token());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return json.readTree(res.body());
    }

    private JsonNode getJson(String path, String token) {
        var res = send("GET", path, null, token);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private String error(java.net.http.HttpResponse<String> res) {
        return json.readTree(res.body()).get("error").get("code").asString();
    }

    private static List<String> strings(JsonNode arr) {
        var out = new ArrayList<String>();
        arr.forEach(n -> out.add(n.asString()));
        return out;
    }
}
