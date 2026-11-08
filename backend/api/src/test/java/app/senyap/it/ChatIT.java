package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.senyap.contracts.rt.RealtimeBus;
import app.senyap.notifications.internal.LoggingPushSender;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bisik (FS-05) lewat REST + delivery router (Kafka → Redis node channel / push mock). */
class ChatIT extends IntegrationTest {

    @Autowired
    JsonMapper json;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisConnectionFactory redisFactory;

    @Autowired
    LoggingPushSender pushes;

    @Autowired
    @Qualifier("chatDatabase")
    ModuleDatabase chatDb;

    record User(String token, String publicId) {
    }

    @Test
    void requestTerimaChatTarikKeluar() {
        var author = login(null);
        var whisperer = login(null);
        String originId = seedOrigin(author, "Aku belum bilang siapa-siapa soal resign.");

        var conv = start(whisperer, originId, "Halo, aku juga pernah begitu.", UUID.randomUUID(), 201);
        String id = conv.get("id").asString();
        assertThat(conv.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(conv.get("role").asString()).isEqualTo("initiator");
        assertThat(conv.get("peer").get("public_id").asString()).isEqualTo(author.publicId());
        assertThat(conv.get("origin").get("excerpt").asString()).startsWith("Aku belum");

        // Pengirim belum bisa mengirim pesan tambahan sebelum diterima.
        assertThat(sendMsg(whisperer, id, UUID.randomUUID(), "lagi").statusCode()).isEqualTo(409);
        // Penerima melihatnya di tab Permintaan, bukan inbox.
        assertThat(ids(list(author, "requests"))).containsExactly(id);
        assertThat(ids(list(author, "inbox"))).doesNotContain(id);
        assertThat(ids(list(whisperer, "inbox"))).containsExactly(id);
        assertThat(json.readTree(send("GET", "/v1/conversations/badge", null, author.token()).body())
                .get("requests").asLong()).isEqualTo(1);

        var accepted = json.readTree(send("POST", "/v1/conversations/" + id + "/accept", null, author.token()).body());
        assertThat(accepted.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(ids(list(author, "inbox"))).containsExactly(id);

        var ack2 = json.readTree(sendMsg(author, id, UUID.randomUUID(), "Makasih ya").body());
        assertThat(ack2.get("seq").asLong()).isEqualTo(2);
        var ack3 = json.readTree(sendMsg(whisperer, id, UUID.randomUUID(), "Sama-sama").body());
        assertThat(ack3.get("seq").asLong()).isEqualTo(3);

        var history = messages(author, id, "");
        assertThat(history).extracting(m -> m.get("seq").asLong()).containsExactly(1L, 2L, 3L);
        assertThat(history.get(0).get("sender").asString()).isEqualTo("them");
        assertThat(history.get(1).get("sender").asString()).isEqualTo("me");
        assertThat(messages(author, id, "?after_seq=1")).extracting(m -> m.get("seq").asLong()).containsExactly(2L, 3L);
        assertThat(messages(author, id, "?before_seq=3&limit=1")).extracting(m -> m.get("seq").asLong())
                .containsExactly(2L);

        // Status delivered/read terlihat oleh lawan bicara.
        assertThat(send("POST", "/v1/conversations/" + id + "/receipts", "{\"read_up_to\":3}", author.token())
                .statusCode()).isEqualTo(204);
        var seen = detail(whisperer, id);
        assertThat(seen.get("peer_read_up_to").asLong()).isEqualTo(3);
        assertThat(seen.get("peer_delivered_up_to").asLong()).isEqualTo(3);
        assertThat(detail(author, id).get("unread_count").asLong()).isZero();

        // Tarik pesan.
        assertThat(send("DELETE", "/v1/conversations/" + id + "/messages/3", null, author.token()).statusCode())
                .isEqualTo(403);
        assertThat(send("DELETE", "/v1/conversations/" + id + "/messages/3", null, whisperer.token()).statusCode())
                .isEqualTo(204);
        var retracted = messages(author, id, "?after_seq=2").getFirst();
        assertThat(retracted.get("retracted").asBoolean()).isTrue();
        assertThat(retracted.has("body")).isFalse();

        // Keluar → CLOSED + pesan sistem untuk lawan bicara.
        assertThat(send("POST", "/v1/conversations/" + id + "/leave", null, whisperer.token()).statusCode())
                .isEqualTo(204);
        var closed = detail(author, id);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        var last = messages(author, id, "?after_seq=3");
        assertThat(last).hasSize(1);
        assertThat(last.getFirst().get("type").asString()).isEqualTo("SYSTEM");
        assertThat(sendMsg(author, id, UUID.randomUUID(), "halo?").statusCode()).isEqualTo(409);
        assertThat(send("GET", "/v1/conversations/" + id, null, whisperer.token()).statusCode()).isEqualTo(404);
    }

    @Test
    void kirimUlangLimaPesanTidakGandaDanBerurutan() {
        var a = login(null);
        var b = login(null);
        String id = activeConversation(a, b);
        var ids = IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
        // Kirim pertama (anggap ack hilang), lalu kirim ulang semuanya dengan client_msg_id yang sama.
        var firstSeqs = new ArrayList<Long>();
        for (int i = 0; i < 5; i++) {
            var res = sendMsg(b, id, ids.get(i), "pesan " + i);
            assertThat(res.statusCode()).isEqualTo(201);
            firstSeqs.add(json.readTree(res.body()).get("seq").asLong());
        }
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 5; i++) {
                var res = sendMsg(b, id, ids.get(i), "pesan " + i);
                assertThat(res.statusCode()).isEqualTo(200);
                assertThat(json.readTree(res.body()).get("seq").asLong()).isEqualTo(firstSeqs.get(i));
            }
        }
        var history = messages(a, id, "?after_seq=1");
        assertThat(history).extracting(m -> m.get("body").asString())
                .containsExactly("pesan 0", "pesan 1", "pesan 2", "pesan 3", "pesan 4");
        assertThat(history).extracting(m -> m.get("seq").asLong()).containsExactly(2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void seqAtomikSaatKirimBersamaan() throws Exception {
        var a = login(null);
        var b = login(null);
        String id = activeConversation(a, b);
        var seqs = new CopyOnWriteArrayList<Long>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 20; i++) {
                var who = i % 2 == 0 ? a : b;
                var cmid = UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    var res = sendMsg(who, id, cmid, "x");
                    seqs.add(json.readTree(res.body()).get("seq").asLong());
                    // kirim ulang bersamaan dengan id yang sama
                    seqs.add(json.readTree(sendMsg(who, id, cmid, "x").body()).get("seq").asLong());
                }));
            }
            for (var f : futures) {
                f.get();
            }
        }
        assertThat(seqs.stream().distinct().sorted().toList())
                .containsExactlyElementsOf(IntStream.rangeClosed(2, 21).mapToObj(Long::valueOf).toList());
        assertThat(messages(a, id, "?after_seq=0&limit=100")).hasSize(21);
    }

    @Test
    void aturanMulaiBisik() {
        var author = login(null);
        var other = login(null);
        String origin = seedOrigin(author, "post");
        // Bisik ke diri sendiri.
        assertThat(startRes(author, origin, "hai", UUID.randomUUID()).statusCode()).isEqualTo(400);
        // Satu Bisik per orang per asal; kirim ulang request yang sama idempotent.
        var cmid = UUID.randomUUID();
        var first = json.readTree(startRes(other, origin, "hai", cmid).body());
        var again = json.readTree(startRes(other, origin, "hai", cmid).body());
        assertThat(again.get("id").asString()).isEqualTo(first.get("id").asString());
        var dup = startRes(other, origin, "hai lagi", UUID.randomUUID());
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(code(dup)).isEqualTo("ALREADY_WHISPERED");
        // Asal tidak ada.
        assertThat(startRes(other, "999999999", "hai", UUID.randomUUID()).statusCode()).isEqualTo(404);
        // Tolak → pengirim melihat DECLINED, penerima tidak melihatnya lagi.
        String id = first.get("id").asString();
        assertThat(send("POST", "/v1/conversations/" + id + "/decline", null, author.token()).statusCode()).isEqualTo(204);
        assertThat(detail(other, id).get("status").asString()).isEqualTo("DECLINED");
        assertThat(ids(list(author, "requests"))).doesNotContain(id);
    }

    @Test
    void blockMenutupPercakapanDanMencegahBisikBaru() {
        var a = login(null);
        var b = login(null);
        String id = activeConversation(a, b);
        assertThat(send("POST", "/v1/conversations/" + id + "/block", null, a.token()).statusCode()).isEqualTo(204);
        // b melihat "lawan bicara telah pergi" (tidak tahu diblokir).
        assertThat(detail(b, id).get("status").asString()).isEqualTo("CLOSED");
        assertThat(messages(b, id, "?after_seq=1").getFirst().get("type").asString()).isEqualTo("SYSTEM");
        // b tidak bisa Bisik lagi ke a dari post mana pun: NOT_FOUND, bukan BLOCKED.
        var res = startRes(b, seedOrigin(a, "post lain"), "hai", UUID.randomUUID());
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(code(res)).isEqualTo("NOT_FOUND");
    }

    @Test
    void kuotaBisikAkunBaru() {
        var w = login(null);
        for (int i = 0; i < 3; i++) {
            assertThat(startRes(w, seedOrigin(login(null), "p" + i), "hai", UUID.randomUUID()).statusCode())
                    .isEqualTo(201);
        }
        var res = startRes(w, seedOrigin(login(null), "p4"), "hai", UUID.randomUUID());
        assertThat(res.statusCode()).isEqualTo(429);
        assertThat(code(res)).isEqualTo("WHISPER_QUOTA_EXCEEDED");
        assertThat(res.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void penerimaOnlineMenerimaFrameLewatNodeChannel() {
        var a = login(null);
        var b = login(null);
        String id = activeConversation(a, b);
        String node = "it-" + UUID.randomUUID();
        // b "terhubung" ke gateway palsu `node`.
        redis.opsForHash().put(RealtimeBus.presenceKey(UUID.fromString(b.publicId())),
                RealtimeBus.presenceField(node, "s1"), Long.toString(System.currentTimeMillis()));
        var received = new CopyOnWriteArrayList<String>();
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisFactory);
        container.addMessageListener((message, pattern) -> received.add(new String(message.getBody())),
                new ChannelTopic(RealtimeBus.nodeChannel(node)));
        container.afterPropertiesSet();
        container.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(container::isListening);
            int pushesBefore = pushes.recent().size();
            sendMsg(a, id, UUID.randomUUID(), "halo online");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received)
                    .anySatisfy(r -> assertThat(r).contains("\"t\":\"msg.new\"").contains("halo online")
                            .contains("\"to\":\"" + b.publicId() + "\"").contains("\"sender\":\"them\"")));
            // Online → tidak ada push DM_MESSAGE.
            assertThat(pushes.recent().subList(pushesBefore, pushes.recent().size()))
                    .noneMatch(p -> "DM_MESSAGE".equals(p.type()) && p.data().get("conversation_id").equals(id));
        } finally {
            container.stop();
            redis.delete(RealtimeBus.presenceKey(UUID.fromString(b.publicId())));
        }
    }

    @Test
    void penerimaOfflineMendapatPushTanpaIsiPesan() {
        var a = login(null);
        var b = login("push-" + UUID.randomUUID());
        String id = activeConversation(a, b);
        sendMsg(a, id, UUID.randomUUID(), "rahasia sekali");
        sendMsg(a, id, UUID.randomUUID(), "rahasia dua");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(pushes.recent())
                .filteredOn(p -> "DM_MESSAGE".equals(p.type()) && id.equals(p.data().get("conversation_id")))
                .hasSize(2)
                .allSatisfy(p -> {
                    assertThat(p.collapseKey()).isEqualTo("conv-" + id);
                    assertThat(p.body()).startsWith("Pesan baru dari ").doesNotContain("rahasia");
                }));
        // Push request + diterima juga terkirim ke device yang offline.
        assertThat(pushes.recent()).anyMatch(p -> "DM_ACCEPTED".equals(p.type()) || "DM_REQUEST".equals(p.type()));
        // Buka app → pesan lengkap.
        assertThat(messages(b, id, "?after_seq=1")).extracting(m -> m.get("body").asString())
                .containsExactly("rahasia sekali", "rahasia dua");
    }

    @Test
    void requestKedaluwarsaSetelahTujuhHari() {
        var a = login(null);
        var b = login(null);
        var conv = start(b, seedOrigin(a, "lama"), "hai", UUID.randomUUID(), 201);
        String id = conv.get("id").asString();
        chatDb.jdbc().sql("UPDATE conversations SET created_at = now() - interval '8 days' WHERE id = :id")
                .param("id", Long.parseLong(id)).update();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(detail(b, id).get("status").asString()).isEqualTo("DECLINED"));
    }

    @Test
    void gambarDmLewatPresignedUpload() throws Exception {
        var a = login(null);
        var b = login(null);
        String id = activeConversation(a, b);
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        var up = json.readTree(send("POST", "/v1/media/dm-uploads",
                "{\"mime_type\":\"image/png\",\"size_bytes\":" + png.length + "}", a.token()).body());
        var put = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(up.get("upload_url").asString()))
                .header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        String mediaId = up.get("media_id").asString();
        assertThat(send("POST", "/v1/media/dm-uploads/" + mediaId + "/complete", null, a.token()).statusCode())
                .isEqualTo(200);
        // b tidak boleh memakai media milik a.
        assertThat(send("POST", "/v1/conversations/" + id + "/messages",
                "{\"client_msg_id\":\"" + UUID.randomUUID() + "\",\"type\":\"IMAGE\",\"media_id\":\"" + mediaId + "\"}",
                b.token()).statusCode()).isEqualTo(400);
        var res = send("POST", "/v1/conversations/" + id + "/messages",
                "{\"client_msg_id\":\"" + UUID.randomUUID() + "\",\"type\":\"IMAGE\",\"media_id\":\"" + mediaId + "\"}",
                a.token());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        var img = messages(b, id, "?after_seq=1").getFirst();
        assertThat(img.get("type").asString()).isEqualTo("IMAGE");
        var get = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(img.get("media").get("url").asString()))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(png);
    }

    @Test
    void sinkronSaatReconnect() {
        var a = login(null);
        var b = login(null);
        String id = activeConversation(a, b);
        String other = activeConversation(a, login(null));
        sendMsg(b, id, UUID.randomUUID(), "baru");
        var res = json.readTree(send("POST", "/v1/conversations/sync",
                "{\"conversations\":[{\"conversation_id\":\"" + id + "\",\"last_seq\":1},"
                        + "{\"conversation_id\":\"" + other + "\",\"last_seq\":1}]}", a.token()).body());
        var changed = new ArrayList<String>();
        res.get("changed").forEach(n -> changed.add(n.get("conversation_id").asString()));
        assertThat(changed).contains(id).doesNotContain(other);
    }

    // ---------------------------------------------------------------- bantu

    private User login(String pushToken) {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var res = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "android", pushToken), null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        String token = json.readTree(res.body()).get("tokens").get("access_token").asString();
        var profile = json.readTree(send("GET", "/v1/me/profile", null, token).body());
        return new User(token, profile.get("public_id").asString());
    }

    private String seedOrigin(User owner, String excerpt) {
        return createPost(owner.token(), excerpt);
    }

    private HttpResponse<String> startRes(User u, String originId, String text, UUID cmid) {
        return send("POST", "/v1/conversations", """
                {"origin_type":"POST","origin_id":"%s","first_message":"%s","client_msg_id":"%s"}"""
                .formatted(originId, text, cmid), u.token());
    }

    private JsonNode start(User u, String originId, String text, UUID cmid, int status) {
        var res = startRes(u, originId, text, cmid);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return json.readTree(res.body());
    }

    /** a menulis post seed, b berbisik, a menerima. */
    private String activeConversation(User a, User b) {
        String id = start(b, seedOrigin(a, "post"), "hai", UUID.randomUUID(), 201).get("id").asString();
        var res = send("POST", "/v1/conversations/" + id + "/accept", null, a.token());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return id;
    }

    private HttpResponse<String> sendMsg(User u, String id, UUID cmid, String body) {
        return send("POST", "/v1/conversations/" + id + "/messages",
                "{\"client_msg_id\":\"" + cmid + "\",\"type\":\"TEXT\",\"body\":\"" + body + "\"}", u.token());
    }

    private List<JsonNode> list(User u, String box) {
        var res = send("GET", "/v1/conversations?box=" + box, null, u.token());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        var out = new ArrayList<JsonNode>();
        json.readTree(res.body()).get("items").forEach(out::add);
        return out;
    }

    private static List<String> ids(List<JsonNode> items) {
        return items.stream().map(n -> n.get("id").asString()).toList();
    }

    private JsonNode detail(User u, String id) {
        var res = send("GET", "/v1/conversations/" + id, null, u.token());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private List<JsonNode> messages(User u, String id, String query) {
        var res = send("GET", "/v1/conversations/" + id + "/messages" + query, null, u.token());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        var out = new ArrayList<JsonNode>();
        json.readTree(res.body()).get("items").forEach(out::add);
        return out;
    }

    private String code(HttpResponse<String> res) {
        return json.readTree(res.body()).get("error").get("code").asString();
    }
}
