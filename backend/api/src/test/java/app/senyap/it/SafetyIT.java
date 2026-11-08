package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.senyap.identity.AccountStatus;
import app.senyap.identity.IdentityApi;
import app.senyap.notifications.internal.LoggingPushSender;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.security.PrincipalResolver;
import app.senyap.support.AdminClient;
import app.senyap.support.IntegrationTest;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fase 4 (FS-07, FS-08, FS-09). Termasuk skenario uji sistem §9 no. 5: report → muncul di konsol moderator;
 * block → pihak yang diblokir tidak bisa mengirim pesan lagi. Plus laporan P0 diproses moderator end-to-end.
 */
class SafetyIT extends IntegrationTest {

    @Autowired
    JsonMapper json;

    @Autowired
    IdentityApi identity;

    @Autowired
    PrincipalResolver principals;

    @Autowired
    LoggingPushSender pushes;

    record User(String token, String publicId) {
        long id(PrincipalResolver p) {
            return p.resolveActiveUserId(UUID.fromString(publicId)).orElseThrow();
        }
    }

    // ------------------------------------------------------------------ §9 no. 5 + P0 end-to-end

    @Test
    void reportMunculDiKonsolBlockMenghentikanPesanDanModeratorMemprosesP0() {
        var a = login();
        var b = login();
        String postA = createPost(a.token(), "Aku capek sama kerjaan, ada yang pernah begini?");
        String conv = whisper(b, postA, "Hai, aku juga pernah");
        assertThat(send("POST", "/v1/conversations/" + conv + "/accept", null, a.token()).statusCode()).isEqualTo(200);
        assertThat(sendMsg(b, conv, "Kasih tahu alamat rumahmu, atau awas kamu ya").statusCode()).isEqualTo(201);

        // A melaporkan pesan seq 2 sebagai ancaman (P0), lalu memblokir dari percakapan.
        var report = send("POST", "/v1/reports", """
                {"target_type":"MESSAGE","target_id":"%s","seq":2,"reason":"threat","note":"dia mengancam"}"""
                .formatted(conv), a.token());
        assertThat(report.statusCode()).as(report.body()).isEqualTo(201);
        String reportId = json.readTree(report.body()).get("id").asString();
        // Kirim ulang = laporan yang sama (idempotent).
        var again = send("POST", "/v1/reports", """
                {"target_type":"MESSAGE","target_id":"%s","seq":2,"reason":"threat"}""".formatted(conv), a.token());
        assertThat(json.readTree(again.body()).get("id").asString()).isEqualTo(reportId);

        // B menarik pesannya: snapshot tetap menyimpan isinya.
        assertThat(send("DELETE", "/v1/conversations/" + conv + "/messages/2", null, b.token()).statusCode())
                .isEqualTo(204);

        assertThat(send("POST", "/v1/blocks", """
                {"target_type":"CONVERSATION","target_id":"%s"}""".formatted(conv), a.token()).statusCode())
                .isEqualTo(204);
        // Block → B tidak bisa mengirim pesan lagi (percakapan tertutup; tidak ada kode "BLOCKED").
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var res = sendMsg(b, conv, "halo?");
            assertThat(res.statusCode()).isEqualTo(409);
            assertThat(code(res)).isEqualTo("CONVERSATION_CLOSED");
        });
        // B juga tidak bisa Bisik lagi ke A dari post lain, dan konten saling tersembunyi.
        String postA2 = createPost(a.token(), "Post kedua A");
        var start = startRes(b, postA2, "hai lagi");
        assertThat(start.statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/posts/" + postA2, null, b.token()).statusCode()).isEqualTo(404);
        String postB = createPost(b.token(), "Post B");
        assertThat(send("GET", "/v1/posts/" + postB, null, a.token()).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/posts/" + postA2 + "/replies", "{\"body\":\"halo\"}", b.token()).statusCode())
                .isEqualTo(404);
        assertThat(feedIds(b, "/v1/feed/latest")).doesNotContain(postA, postA2);
        // Daftar block A: alias + tanggal, tanpa public_id.
        var blocks = json.readTree(send("GET", "/v1/me/blocks", null, a.token()).body()).get("items");
        assertThat(blocks.size()).isEqualTo(1);
        assertThat(blocks.get(0).has("public_id")).isFalse();
        assertThat(blocks.get(0).get("name").asString()).isNotBlank();

        // Report muncul di konsol moderator, antrean P0.
        var mod = new AdminClient(port).login("moderator1", "MODERATOR");
        long caseId = Tsid.parse(reportId);
        var queue = mod.get("/admin?priority=0");
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(queue.body()).contains("/admin/cases/" + caseId);
        var detail = mod.get("/admin/cases/" + caseId);
        assertThat(detail.body()).contains("Kasus", "threat", "Kasih tahu alamat rumahmu", "AKN-")
                .contains("dia mengancam");
        assertThat(detail.body()).doesNotContain(Long.toString(b.id(principals)));

        // Moderator: aksi tanpa alasan ditolak; BAN dengan alasan → akun B tidak bisa dipakai, pelapor diberi tahu.
        int pushesBefore = pushes.recent().size();
        mod.post("/admin/cases/" + caseId + "/actions", Map.of("action", "BAN", "reason", " "));
        assertThat(mod.get("/admin/cases/" + caseId).body()).contains("OPEN");
        var banned = mod.post("/admin/cases/" + caseId + "/actions",
                Map.of("action", "BAN", "reason", "Ancaman kekerasan di Bisik"));
        assertThat(banned.statusCode()).isEqualTo(302);
        assertThat(mod.get("/admin/cases/" + caseId).body()).contains("ACTIONED", "BAN", "dev:moderator1");
        assertThat(send("GET", "/v1/me/profile", null, b.token()).statusCode()).isEqualTo(401);
        assertThat(pushes.recent().subList(pushesBefore, pushes.recent().size()))
                .anyMatch(p -> p.type().equals("REPORT_UPDATE") && p.body().contains("Terima kasih"));
        assertThat(mod.get("/admin?priority=0").body()).doesNotContain("/admin/cases/" + caseId + "\"");
        var audit = mod.get("/admin/audit").body();
        assertThat(audit).contains("VIEW_CASE", "ACTION_BAN", "dev:moderator1");

        // Penelusuran identitas: moderator biasa ditolak; peran legal dengan alasan → tercatat.
        assertThat(mod.get("/admin/legal/cases/" + caseId + "/identity").statusCode()).isEqualTo(403);
        var legal = new AdminClient(port).login("legal1", "LEGAL");
        legal.get("/admin/legal/cases/" + caseId + "/identity");
        var shown = legal.post("/admin/legal/cases/" + caseId + "/identity",
                Map.of("reason", "Surat Polda No. 123/XI/2026"));
        assertThat(shown.body()).contains("phone_hmac", "User ID internal");
        assertThat(legal.get("/admin/audit").body()).contains("LEGAL_IDENTITY_LOOKUP", "Surat Polda");
    }

    @Test
    void konsolWajibLoginDanPeran() {
        var anon = new AdminClient(port);
        var res = anon.get("/admin");
        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("Location").orElse("")).contains("/admin/login");
        assertThat(anon.get("/admin/login").body()).contains("Login dev");
        // API publik tetap tanpa sesi/CSRF.
        assertThat(send("GET", "/v1/support/self-harm", null, null).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ FS-08

    @Test
    void filterKataDanPolaDoxxing() {
        var a = login();
        var b = login();
        String post = createPost(a.token(), "Post biasa");
        for (String body : List.of("WA aku 0812-3456-7890 ya", "NIK ku 3201234567890123", "rumahku Jl. Melati Indah No. 12",
                "RT 03/RW 05 dekat masjid", "email aku kamu@contoh.com")) {
            var res = postRes(a, body);
            assertThat(res.statusCode()).as(body).isEqualTo(422);
            assertThat(code(res)).isEqualTo("CONTENT_REJECTED");
            assertThat(message(res)).contains("nomor HP");
        }
        var reply = send("POST", "/v1/posts/" + post + "/replies", "{\"body\":\"ada p3d0fil di sini\"}", b.token());
        assertThat(reply.statusCode()).isEqualTo(422);
        assertThat(message(reply)).contains("tidak diizinkan");
        // Kata terlarang juga berlaku di Bisik; pola doxxing tidak (DM privat).
        var start = startRes(b, post, "pedofil");
        assertThat(start.statusCode()).isEqualTo(422);
        String conv = whisper(b, post, "nomorku 081234567890 kalau mau ngobrol");
        assertThat(conv).isNotBlank();
    }

    @Test
    void risikoMenyakitiDiriKartuBantuanDanAntreanP0() {
        var a = login();
        var b = login();
        var res = postRes(a, "Rasanya aku ingin mati saja malam ini");
        assertThat(res.statusCode()).isEqualTo(201);
        var post = json.readTree(res.body());
        assertThat(post.get("support_card").asBoolean()).isTrue();
        String id = post.get("id").asString();
        // Konten tidak diblokir; viewer lain tidak menerima support_card.
        var seen = json.readTree(send("GET", "/v1/posts/" + id, null, b.token()).body());
        assertThat(seen.has("support_card")).isFalse();
        var card = json.readTree(send("GET", "/v1/support/self-harm", null, a.token()).body());
        assertThat(card.get("verified").asBoolean()).isFalse();
        assertThat(card.get("hotlines").get(0).get("contact").asString()).contains("BELUM-DIVERIFIKASI");

        var mod = new AdminClient(port).login("moderator2", "MODERATOR");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(mod.get("/admin?priority=0").body()).contains("ingin mati saja"));
    }

    @Test
    void ancamanDitahanHanyaTerlihatPenulisSampaiModeratorMemulihkan() {
        var a = login();
        var b = login();
        var res = postRes(a, "Gue bunuh lo kalau ketemu");
        assertThat(res.statusCode()).isEqualTo(201);
        String id = json.readTree(res.body()).get("id").asString();
        assertThat(send("GET", "/v1/posts/" + id, null, a.token()).statusCode()).isEqualTo(200);
        assertThat(feedIds(a, "/v1/feed/latest")).contains(id);
        assertThat(send("GET", "/v1/posts/" + id, null, b.token()).statusCode()).isEqualTo(404);

        var mod = new AdminClient(port).login("moderator3", "MODERATOR");
        long caseId = await().atMost(Duration.ofSeconds(30)).until(() -> caseIdFor(mod, "Gue bunuh lo"), c -> c != null);
        mod.get("/admin/cases/" + caseId);
        mod.post("/admin/cases/" + caseId + "/actions", Map.of("action", "RESTORE_CONTENT", "reason", "Bercanda antar teman"));
        assertThat(send("GET", "/v1/posts/" + id, null, b.token()).statusCode()).isEqualTo(200);
        mod.post("/admin/cases/" + caseId + "/actions", Map.of("action", "HIDE_CONTENT", "reason", "Ditinjau ulang: ancaman"));
        assertThat(send("GET", "/v1/posts/" + id, null, b.token()).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/posts/" + id, null, a.token()).statusCode()).isEqualTo(404);
    }

    @Test
    void shadowRestrictionDanKuotaTrustRendah() {
        var a = login();
        var b = login();
        String seed = createPost(b.token(), "Post untuk dilaporkan");
        // b melaporkan post a → moderator membatasi a 24 jam.
        String target = createPost(a.token(), "Konten spam beli followers");
        var rep = send("POST", "/v1/reports", """
                {"target_type":"POST","target_id":"%s","reason":"spam"}""".formatted(target), b.token());
        long caseId = Tsid.parse(json.readTree(rep.body()).get("id").asString());
        var mod = new AdminClient(port).login("moderator4", "MODERATOR");
        mod.get("/admin/cases/" + caseId);
        mod.post("/admin/cases/" + caseId + "/actions", Map.of("action", "RESTRICT_24H", "reason", "Spam berulang"));
        var standing = identity.findStandings(List.of(a.id(principals))).get(a.id(principals));
        assertThat(standing.status()).isEqualTo(AccountStatus.RESTRICTED);
        assertThat(standing.trustScore()).isLessThan(50);

        // Post baru a hanya terlihat a sendiri (tanpa penanda apa pun di respons).
        var res = postRes(a, "Halo semua");
        assertThat(res.statusCode()).isEqualTo(201);
        String shadow = json.readTree(res.body()).get("id").asString();
        assertThat(res.body()).doesNotContain("restrict", "AUTHOR_ONLY");
        assertThat(send("GET", "/v1/posts/" + shadow, null, a.token()).statusCode()).isEqualTo(200);
        assertThat(feedIds(a, "/v1/feed/latest")).contains(shadow);
        assertThat(send("GET", "/v1/posts/" + shadow, null, b.token()).statusCode()).isEqualTo(404);
        // Trust rendah: Bisik baru 0/hari, upload 0/hari.
        var w = startRes(a, seed, "hai");
        assertThat(w.statusCode()).isEqualTo(429);
        assertThat(code(w)).isEqualTo("WHISPER_QUOTA_EXCEEDED");
        var up = send("POST", "/v1/media/uploads", "{\"mime_type\":\"image/png\",\"size_bytes\":100}", a.token());
        assertThat(code(up)).isEqualTo("UPLOAD_QUOTA_EXCEEDED");
    }

    @Test
    void diblokirMenurunkanTrustScore() {
        var a = login();
        var b = login();
        String postB = createPost(b.token(), "post b");
        long bId = b.id(principals);
        int before = identity.findStandings(List.of(bId)).get(bId).trustScore();
        assertThat(send("POST", "/v1/blocks", "{\"target_type\":\"POST\",\"target_id\":\"" + postB + "\"}", a.token())
                .statusCode()).isEqualTo(204);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(identity.findStandings(List.of(bId)).get(bId).trustScore()).isEqualTo(before - 5));
        // Buka block lewat daftar; blokir diri sendiri ditolak.
        String blockId = json.readTree(send("GET", "/v1/me/blocks", null, a.token()).body()).get("items").get(0)
                .get("id").asString();
        assertThat(send("DELETE", "/v1/me/blocks/" + blockId, null, a.token()).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/v1/posts/" + postB, null, a.token()).statusCode()).isEqualTo(200);
        String postA = createPost(a.token(), "post a");
        assertThat(send("POST", "/v1/blocks", "{\"target_type\":\"POST\",\"target_id\":\"" + postA + "\"}", a.token())
                .statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/reports", """
                {"target_type":"POST","target_id":"%s","reason":"spam"}""".formatted(postA), a.token()).statusCode())
                .isEqualTo(400);
        assertThat(send("POST", "/v1/reports", """
                {"target_type":"POST","target_id":"%s","reason":"bukan-alasan"}""".formatted(postB), a.token())
                .statusCode()).isEqualTo(400);
    }

    @Test
    void hashGambarCocokDitolakAkunDisuspendDanLaporanP0() throws Exception {
        var a = login();
        String mediaId = upload(a, gradientPng(640, 480));
        assertThat(send("POST", "/v1/media/" + mediaId + "/complete", null, a.token()).statusCode()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(json.readTree(
                send("GET", "/v1/media/" + mediaId, null, a.token()).body()).get("status").asString())
                .isEqualTo("REJECTED"));
        long aId = a.id(principals);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(identity.findStandings(List.of(aId)).get(aId).status()).isEqualTo(AccountStatus.SUSPENDED));
        var post = postRes(a, "halo");
        assertThat(post.statusCode()).isEqualTo(403);
        var mod = new AdminClient(port).login("moderator5", "MODERATOR");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(mod.get("/admin?priority=0").body()).contains("daftar hash", mediaId));
    }

    // ------------------------------------------------------------------ bantu

    private User login() {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var res = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "android", "push-" + UUID.randomUUID()), null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        String token = json.readTree(res.body()).get("tokens").get("access_token").asString();
        var profile = json.readTree(send("GET", "/v1/me/profile", null, token).body());
        return new User(token, profile.get("public_id").asString());
    }

    private HttpResponse<String> postRes(User u, String body) {
        var bg = json.readTree(send("GET", "/v1/backgrounds?category=curhat", null, u.token()).body())
                .get("items").get(0).get("id").asString();
        return send("POST", "/v1/posts", json.createObjectNode().put("body", body).put("category", "curhat")
                .put("bg_media_id", bg).toString(), u.token());
    }

    private HttpResponse<String> startRes(User u, String postId, String text) {
        return send("POST", "/v1/conversations", json.createObjectNode().put("origin_type", "POST")
                .put("origin_id", postId).put("first_message", text).put("client_msg_id", UUID.randomUUID().toString())
                .toString(), u.token());
    }

    private String whisper(User u, String postId, String text) {
        var res = startRes(u, postId, text);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return json.readTree(res.body()).get("id").asString();
    }

    private HttpResponse<String> sendMsg(User u, String conv, String body) {
        return send("POST", "/v1/conversations/" + conv + "/messages", json.createObjectNode()
                .put("client_msg_id", UUID.randomUUID().toString()).put("type", "TEXT").put("body", body).toString(),
                u.token());
    }

    private List<String> feedIds(User u, String path) {
        var out = new ArrayList<String>();
        json.readTree(send("GET", path, null, u.token()).body()).get("items").forEach(n -> out.add(n.get("id").asString()));
        return out;
    }

    private Long caseIdFor(AdminClient mod, String excerpt) {
        var html = mod.get("/admin").body();
        var m = Pattern.compile("/admin/cases/(\\d+)\">\\d+</a></td>\\s*<td>[A-Z]+</td>\\s*<td>[a-z_]+</td>\\s*"
                + "<td>[A-Z]+</td>\\s*<td>([^<]*)</td>").matcher(html);
        while (m.find()) {
            if (m.group(2).contains(excerpt)) {
                return Long.parseLong(m.group(1));
            }
        }
        return null;
    }

    private String code(HttpResponse<String> res) {
        return json.readTree(res.body()).get("error").get("code").asString();
    }

    private String message(HttpResponse<String> res) {
        return json.readTree(res.body()).get("error").get("message").asString();
    }

    private String upload(User u, byte[] bytes) throws IOException, InterruptedException {
        var res = send("POST", "/v1/media/uploads",
                "{\"mime_type\":\"image/png\",\"size_bytes\":%d}".formatted(bytes.length), u.token());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        JsonNode ticket = json.readTree(res.body());
        var put = HttpRequest.newBuilder(URI.create(ticket.get("upload_url").asString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        for (var h : ticket.get("upload_headers").properties()) {
            if (!h.getKey().equalsIgnoreCase("content-length")) {
                put.header(h.getKey(), h.getValue().asString());
            }
        }
        var stored = HttpClient.newHttpClient().send(put.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(stored.statusCode()).isEqualTo(200);
        return ticket.get("media_id").asString();
    }

    /** Gradien horizontal terang → gelap: dHash = semua bit 1, ada di daftar hash khusus test. */
    private static byte[] gradientPng(int w, int h) throws IOException {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.WHITE, w, 0, Color.BLACK));
        g.fillRect(0, 0, w, h);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
