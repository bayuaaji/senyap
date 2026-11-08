package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.senyap.support.IntegrationTest;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upload latar sendiri (functional spec FS-02): presigned PUT ke MinIO → complete → worker (cek magic bytes,
 * resize, blur, overlay, blurhash) → READY → dipakai post. Latar milik orang lain tidak bisa dipakai.
 */
class MediaUploadIT extends IntegrationTest {

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void uploadLatarSendiriSampaiDipakaiPost() throws Exception {
        String a = login();
        String b = login();
        byte[] png = png(2400, 1600);

        String mediaId = upload(a, "image/png", png);
        var complete = send("POST", "/v1/media/" + mediaId + "/complete", null, a);
        assertThat(complete.statusCode()).as(complete.body()).isEqualTo(202);

        var ready = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode s = json.readTree(send("GET", "/v1/media/" + mediaId, null, a).body());
            assertThat(s.get("status").asString()).isEqualTo("READY");
            ready[0] = s;
        });
        String url = ready[0].get("background").get("url").asString();
        assertThat(url).endsWith("/public/bg/" + mediaId + ".jpg");
        assertThat(ready[0].get("background").get("blurhash").asString()).hasSizeGreaterThan(6);
        // Hasil pipeline bisa dibaca publik, sudah di-resize ke 1080 px dan berformat JPEG
        var img = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(img.statusCode()).isEqualTo(200);
        BufferedImage out = ImageIO.read(new java.io.ByteArrayInputStream(img.body()));
        assertThat(Math.max(out.getWidth(), out.getHeight())).isEqualTo(1080);
        assertThat(img.body()[0] & 0xff).isEqualTo(0xFF);

        // Status media orang lain: 404; latar orang lain tidak bisa dipakai post
        assertThat(send("GET", "/v1/media/" + mediaId, null, b).statusCode()).isEqualTo(404);
        var stolen = send("POST", "/v1/posts", "{\"body\":\"hai\",\"category\":\"curhat\",\"bg_media_id\":\"" + mediaId + "\"}", b);
        assertThat(stolen.statusCode()).isEqualTo(400);

        var post = send("POST", "/v1/posts", "{\"body\":\"hai\",\"category\":\"curhat\",\"bg_media_id\":\"" + mediaId + "\"}", a);
        assertThat(post.statusCode()).as(post.body()).isEqualTo(201);
        assertThat(json.readTree(post.body()).get("background").get("url").asString()).isEqualTo(url);
    }

    @Test
    void fileBukanGambarDitolakPipeline() throws Exception {
        String a = login();
        byte[] fake = "ini bukan gambar sama sekali".getBytes(StandardCharsets.UTF_8);
        String mediaId = upload(a, "image/png", fake);
        assertThat(send("POST", "/v1/media/" + mediaId + "/complete", null, a).statusCode()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(json.readTree(send("GET", "/v1/media/" + mediaId, null, a).body()).get("status").asString())
                        .isEqualTo("REJECTED"));
    }

    @Test
    void validasiUpload() {
        String a = login();
        assertThat(send("POST", "/v1/media/uploads", "{\"mime_type\":\"image/gif\",\"size_bytes\":100}", a).statusCode())
                .isEqualTo(400);
        assertThat(send("POST", "/v1/media/uploads", "{\"mime_type\":\"image/jpeg\",\"size_bytes\":99999999}", a).statusCode())
                .isEqualTo(400);
        // complete sebelum PUT: belum ada objek
        var res = send("POST", "/v1/media/uploads", "{\"mime_type\":\"image/jpeg\",\"size_bytes\":100}", a);
        String id = json.readTree(res.body()).get("media_id").asString();
        assertThat(send("POST", "/v1/media/" + id + "/complete", null, a).statusCode()).isEqualTo(400);
        // kuota akun baru: 5/hari (satu sudah terpakai di atas)
        for (int i = 0; i < 4; i++) {
            assertThat(send("POST", "/v1/media/uploads", "{\"mime_type\":\"image/jpeg\",\"size_bytes\":100}", a).statusCode())
                    .isEqualTo(201);
        }
        var quota = send("POST", "/v1/media/uploads", "{\"mime_type\":\"image/jpeg\",\"size_bytes\":100}", a);
        assertThat(quota.statusCode()).isEqualTo(429);
        assertThat(json.readTree(quota.body()).get("error").get("code").asString()).isEqualTo("UPLOAD_QUOTA_EXCEEDED");
    }

    private String upload(String token, String mime, byte[] bytes) throws IOException, InterruptedException {
        var res = send("POST", "/v1/media/uploads",
                "{\"mime_type\":\"%s\",\"size_bytes\":%d}".formatted(mime, bytes.length), token);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        JsonNode ticket = json.readTree(res.body());
        assertThat(ticket.get("upload_method").asString()).isEqualTo("PUT");
        var put = HttpRequest.newBuilder(URI.create(ticket.get("upload_url").asString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        for (var h : ticket.get("upload_headers").properties()) {
            if (!h.getKey().equalsIgnoreCase("content-length")) { // diisi HttpClient sendiri
                put.header(h.getKey(), h.getValue().asString());
            }
        }
        var stored = http.send(put.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(stored.statusCode()).as(stored.body()).isEqualTo(200);
        return ticket.get("media_id").asString();
    }

    private static byte[] png(int w, int h) throws IOException {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color(240, 180, 60));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(30, 90, 200));
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String login() {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var res = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body()).get("tokens").get("access_token").asString();
    }
}
