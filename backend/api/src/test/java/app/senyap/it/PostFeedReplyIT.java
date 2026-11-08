package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.senyap.posts.OriginRef;
import app.senyap.posts.OriginType;
import app.senyap.posts.PostsApi;
import app.senyap.posts.WhisperOrigin;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Skenario "selesai jika" Fase 3 (build guide §4), sebatas tanpa chat: A membuat post dengan latar galeri +
 * lokasi, B melihatnya di Terbaru, Sekitar, Kategori, dan Populer, membalas; alias + lencana Penulis benar;
 * asal Bisik divalidasi lewat {@link PostsApi#findOrigins}. Penjaga anonimitas memeriksa semua respons.
 */
class PostFeedReplyIT extends IntegrationTest {

    /** Yogyakarta (≈ -7.7956, 110.3695) dan sel ±4 km di timurnya; Jakarta jauh. */
    static final String YOGYA = "qqw7xsb";
    static final String YOGYA_EAST = "qqwe8kn";
    static final String JAKARTA = "qqguxmd";

    @Autowired
    JsonMapper json;

    @Autowired
    PostsApi postsApi;

    @Autowired
    app.senyap.media.MediaApi mediaApi;

    @Autowired
    app.senyap.shared.id.Tsid tsid;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    @Qualifier("identityDatabase")
    ModuleDatabase identity;

    @Autowired
    @Qualifier("postsDatabase")
    ModuleDatabase posts;

    record User(String token, String publicId, long userId, JsonNode alias) {
    }

    @Test
    void aMembuatPostBMelihatMembalasDanAsalBisikValid() {
        User a = login();
        User b = login();
        String bg = builtinBackground("curhat");

        // A membuat post dengan lokasi
        var created = send("POST", "/v1/posts", """
                {"body":"  Aku belum bilang ke siapa-siapa kalau aku resign minggu depan.  ","category":"kerja",
                 "bg_media_id":"%s","use_location":true,"geohash":"%s"}""".formatted(bg, YOGYA), a.token());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode post = json.readTree(created.body());
        String postId = post.get("id").asString();
        assertThat(post.get("body").asString()).isEqualTo("Aku belum bilang ke siapa-siapa kalau aku resign minggu depan.");
        assertThat(post.get("city").asString()).isEqualTo("Yogyakarta");
        assertThat(post.get("is_mine").asBoolean()).isTrue();
        assertThat(post.get("can_whisper").asBoolean()).isFalse();
        assertThat(post.get("author").get("public_id").asString()).isEqualTo(a.publicId());
        assertThat(post.get("author").get("name").asString()).isEqualTo(a.alias().get("name").asString());
        assertThat(post.get("background").get("url").asString()).contains("/public/gallery/");
        assertThat(post.get("background").get("blurhash").asString()).isNotBlank();
        assertThat(post.has("geohash")).isFalse();
        Instant createdAt = Instant.parse(post.get("created_at").asString());
        assertThat(createdAt.getEpochSecond() % 60).as("created_at dibulatkan ke menit").isZero();

        // B melihat di Terbaru, Sekitar (sel tetangga), Kategori; tidak di Sekitar Jakarta
        clearFeedCache();
        JsonNode latest = getJson("/v1/feed/latest", b.token());
        JsonNode inLatest = find(latest, postId);
        assertThat(inLatest).as("post A di Terbaru").isNotNull();
        assertThat(inLatest.get("is_mine").asBoolean()).isFalse();
        assertThat(inLatest.get("can_whisper").asBoolean()).isTrue();
        assertThat(find(getJson("/v1/feed/nearby?cell=" + YOGYA_EAST, b.token()), postId)).as("Sekitar").isNotNull();
        assertThat(find(getJson("/v1/feed/nearby?cell=" + JAKARTA, b.token()), postId)).isNull();
        assertThat(find(getJson("/v1/feed/category/kerja", b.token()), postId)).isNotNull();
        assertThat(find(getJson("/v1/feed/category/cinta", b.token()), postId)).isNull();

        // B membalas, A (pembuat post) membalas → lencana Penulis hanya untuk A
        var replyB = send("POST", "/v1/posts/" + postId + "/replies", "{\"body\":\"Semangat ya, kamu pasti bisa.\"}", b.token());
        assertThat(replyB.statusCode()).as(replyB.body()).isEqualTo(201);
        String replyBId = json.readTree(replyB.body()).get("id").asString();
        var replyA = send("POST", "/v1/posts/" + postId + "/replies", "{\"body\":\"Makasih banyak.\"}", a.token());
        assertThat(replyA.statusCode()).isEqualTo(201);

        JsonNode replies = getJson("/v1/posts/" + postId + "/replies", b.token()).get("items");
        assertThat(replies.size()).isEqualTo(2);
        JsonNode first = replies.get(0);
        JsonNode second = replies.get(1);
        assertThat(first.get("id").asString()).as("urut terlama → terbaru").isEqualTo(replyBId);
        assertThat(first.get("author").get("public_id").asString()).isEqualTo(b.publicId());
        assertThat(first.get("author").get("name").asString()).isEqualTo(b.alias().get("name").asString());
        assertThat(first.get("author").get("tag").asString()).isEqualTo(b.alias().get("tag").asString());
        assertThat(first.get("author").get("is_post_author").asBoolean()).isFalse();
        assertThat(first.get("is_mine").asBoolean()).isTrue();
        assertThat(second.get("author").get("public_id").asString()).isEqualTo(a.publicId());
        assertThat(second.get("author").get("is_post_author").asBoolean()).as("lencana Penulis").isTrue();
        assertThat(second.get("can_whisper").asBoolean()).isTrue();
        assertThat(getJson("/v1/posts/" + postId, b.token()).get("reply_count").asInt()).isEqualTo(2);

        // Asal Bisik lewat interface batch modul posts (untuk modul chat)
        long postIdL = Long.parseLong(postId);
        long replyBIdL = Long.parseLong(replyBId);
        List<WhisperOrigin> origins = postsApi.findOrigins(List.of(
                new OriginRef(OriginType.POST, postIdL), new OriginRef(OriginType.REPLY, replyBIdL),
                new OriginRef(OriginType.REPLY, 42L)));
        assertThat(origins).hasSize(2);
        var fromPost = origins.stream().filter(o -> o.ref().type() == OriginType.POST).findFirst().orElseThrow();
        assertThat(fromPost.ownerUserId()).isEqualTo(a.userId());
        assertThat(fromPost.postId()).isEqualTo(postIdL);
        var fromReply = origins.stream().filter(o -> o.ref().type() == OriginType.REPLY).findFirst().orElseThrow();
        assertThat(fromReply.ownerUserId()).isEqualTo(b.userId());
        assertThat(fromReply.postId()).isEqualTo(postIdL);
        assertThat(fromReply.excerpt()).isEqualTo("Semangat ya, kamu pasti bisa.");

        // Hati: idempotent, counter terbatch
        assertThat(send("PUT", "/v1/posts/" + postId + "/heart", null, b.token()).statusCode()).isEqualTo(204);
        assertThat(send("PUT", "/v1/posts/" + postId + "/heart", null, b.token()).statusCode()).isEqualTo(204);
        assertThat(getJson("/v1/posts/" + postId, b.token()).get("viewer_has_hearted").asBoolean()).isTrue();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getJson("/v1/posts/" + postId, a.token()).get("heart_count").asInt()).isEqualTo(1));

        // Populer: sorted set dibangun ulang dari hot_score
        redis.delete("feed:hot");
        assertThat(find(getJson("/v1/feed/popular", b.token()), postId)).isNotNull();

        // Pembuat post menyembunyikan balasan B → bukan asal Bisik lagi, tidak terlihat user lain
        assertThat(send("PUT", "/v1/replies/" + replyBId + "/hidden", null, b.token()).statusCode()).isEqualTo(403);
        assertThat(send("PUT", "/v1/replies/" + replyBId + "/hidden", null, a.token()).statusCode()).isEqualTo(204);
        assertThat(postsApi.findOrigins(List.of(new OriginRef(OriginType.REPLY, replyBIdL)))).isEmpty();
        User c = login();
        assertThat(getJson("/v1/posts/" + postId + "/replies", c.token()).get("items").size()).isEqualTo(1);
        assertThat(getJson("/v1/posts/" + postId + "/replies", b.token()).get("items").size()).as("penulis tetap melihat").isEqualTo(2);

        // Hapus: hanya pembuat; setelah dihapus 404 dan bukan asal Bisik
        assertThat(send("DELETE", "/v1/posts/" + postId, null, b.token()).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/v1/posts/" + postId, null, a.token()).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/v1/posts/" + postId, null, b.token()).statusCode()).isEqualTo(404);
        assertThat(postsApi.findOrigins(List.of(new OriginRef(OriginType.POST, postIdL)))).isEmpty();
        clearFeedCache();
        assertThat(find(getJson("/v1/feed/latest", b.token()), postId)).isNull();
    }

    @Test
    void validasiPost() {
        User a = login();
        String bg = builtinBackground("cinta");
        assertThat(code(send("POST", "/v1/posts", "{\"body\":\"   \",\"category\":\"cinta\",\"bg_media_id\":\"" + bg + "\"}", a.token())))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(send("POST", "/v1/posts", "{\"body\":\"" + "a".repeat(251) + "\",\"category\":\"cinta\",\"bg_media_id\":\"" + bg + "\"}", a.token())))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(send("POST", "/v1/posts", "{\"body\":\"hai\",\"category\":\"gosip\",\"bg_media_id\":\"" + bg + "\"}", a.token())))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(send("POST", "/v1/posts", "{\"body\":\"hai\",\"category\":\"cinta\",\"bg_media_id\":\"123\"}", a.token())))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(send("POST", "/v1/posts", "{\"body\":\"hai\",\"category\":\"cinta\",\"bg_media_id\":\"" + bg + "\",\"use_location\":true}", a.token())))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(send("GET", "/v1/feed/nearby?cell=abc", null, a.token()))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(send("GET", "/v1/feed/latest?cursor=rusak!", null, a.token()))).isEqualTo("VALIDATION_FAILED");
        assertThat(send("GET", "/v1/feed/latest", null, null).statusCode()).isEqualTo(401);

        // Tanpa lokasi: tidak ada kota; post milik sendiri muncul di /v1/me/posts
        var res = send("POST", "/v1/posts", "{\"body\":\"hai\",\"category\":\"cinta\",\"bg_media_id\":\"" + bg + "\"}", a.token());
        assertThat(res.statusCode()).isEqualTo(201);
        JsonNode post = json.readTree(res.body());
        assertThat(post.get("city").isNull()).isTrue();
        assertThat(find(getJson("/v1/me/posts", a.token()), post.get("id").asString())).isNotNull();
    }

    @Test
    void kuotaPostAkunBaru() {
        User a = login();
        String bg = builtinBackground("tanya");
        String body = "{\"body\":\"tanya\",\"category\":\"tanya\",\"bg_media_id\":\"" + bg + "\"}";
        for (int i = 0; i < 3; i++) {
            assertThat(send("POST", "/v1/posts", body, a.token()).statusCode()).isEqualTo(201);
        }
        var quota = send("POST", "/v1/posts", body, a.token());
        assertThat(quota.statusCode()).isEqualTo(429);
        assertThat(code(quota)).isEqualTo("POST_QUOTA_EXCEEDED");
        assertThat(quota.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void paginasiCursorFeedKategori() {
        User a = login();
        String bg = builtinBackground("keluarga");
        long author = a.userId();
        // 45 post langsung di DB (kuota akun baru 3/hari); ID TSID berurut waktu
        for (int i = 0; i < 45; i++) {
            posts.jdbc().sql("""
                            INSERT INTO posts (id, author_id, body, bg_media_id, category)
                            VALUES (:id, :a, :b, :bg, 'keluarga')""")
                    .param("id", tsid.next()).param("a", author).param("b", "keluarga " + i).param("bg", Long.parseLong(bg)).update();
        }
        clearFeedCache();
        var seen = new ArrayList<Long>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = getJson("/v1/feed/category/keluarga" + (cursor == null ? "" : "?cursor=" + cursor), a.token());
            assertThat(page.get("items").size()).isLessThanOrEqualTo(20);
            for (JsonNode item : page.get("items")) {
                seen.add(Long.parseLong(item.get("id").asString()));
                assertThat(item.get("category").asString()).isEqualTo("keluarga");
            }
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(seen).hasSizeGreaterThanOrEqualTo(45).doesNotHaveDuplicates();
        assertThat(seen).isSortedAccordingTo((x, y) -> Long.compare(y, x));
    }

    // ---- helper --------------------------------------------------------------------------------

    private User login() {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var res = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", null), null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        String token = json.readTree(res.body()).get("tokens").get("access_token").asString();
        JsonNode profile = getJson("/v1/me/profile", token);
        String publicId = profile.get("public_id").asString();
        long userId = identity.jdbc().sql("SELECT id FROM users WHERE public_id = :p")
                .param("p", UUID.fromString(publicId)).query(Long.class).single();
        return new User(token, publicId, userId, profile.get("alias"));
    }

    /** Galeri bawaan diisi worker secara asinkron saat start. */
    private String builtinBackground(String category) {
        User viewer = login();
        var holder = new String[1];
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            JsonNode items = getJson("/v1/backgrounds?category=" + category, viewer.token()).get("items");
            assertThat(items.size()).isPositive();
            assertThat(items.get(0).get("blurhash").asString()).isNotBlank();
            holder[0] = items.get(0).get("id").asString();
        });
        assertThat(mediaApi.findBackgrounds(List.of(Long.parseLong(holder[0]))))
                .as("MediaApi menemukan latar galeri " + holder[0]).containsKey(Long.parseLong(holder[0]));
        return holder[0];
    }

    private JsonNode getJson(String path, String token) {
        var res = send("GET", path, null, token);
        assertThat(res.statusCode()).as(path + " → " + res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private static JsonNode find(JsonNode page, String id) {
        for (JsonNode item : page.get("items")) {
            if (item.get("id").asString().equals(id)) {
                return item;
            }
        }
        return null;
    }

    private String code(java.net.http.HttpResponse<String> res) {
        return json.readTree(res.body()).get("error").get("code").asString();
    }

    private void clearFeedCache() {
        var keys = redis.keys("feed:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
