package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.senyap.notifications.internal.LoggingPushSender;
import app.senyap.support.GatewayProcess;
import app.senyap.support.IntegrationTest;
import app.senyap.support.WsClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Selesai jika" Fase 2 (build guide §4) end-to-end: api (proses test ini, profil test,api,worker) + gateway
 * realtime sebagai <b>proses terpisah</b> dari jarnya + dua klien WebSocket nyata. Dilewati bila jar gateway
 * belum di-build ({@code ./mvnw verify} dari {@code backend/} membangunnya lebih dulu).
 */
class RealtimeE2EIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RealtimeE2EIT.class);
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static GatewayProcess gateway;

    @Autowired
    JsonMapper json;

    @Autowired
    LoggingPushSender pushes;

    record User(String token, String publicId) {
    }

    @BeforeEach
    void gateway() {
        var jar = GatewayProcess.findJar();
        Assumptions.assumeTrue(jar != null, "jar realtime belum di-build");
        if (gateway == null) {
            gateway = new GatewayProcess(jar, port, REDIS.getHost(), REDIS.getMappedPort(6379));
            gateway.start();
        }
    }

    @AfterAll
    static void stopGateway() {
        if (gateway != null) {
            gateway.stop();
            gateway = null;
        }
    }

    @Test
    void duaKlienChatRealtimeDenganLatensiP95() {
        var a = login(null);
        var b = login(null);
        String conv = activeConversation(a, b);
        try (var wa = WsClient.connect(gateway.port(), a.token()); var wb = WsClient.connect(gateway.port(), b.token())) {
            ping(wa);
            ping(wb);
            // pemanasan (JIT, koneksi pool)
            for (int i = 0; i < 5; i++) {
                roundTrip(wa, wb, conv, "pemanasan " + i);
            }
            var latencies = new ArrayList<Long>();
            for (int i = 0; i < 50; i++) {
                latencies.add(roundTrip(i % 2 == 0 ? wa : wb, i % 2 == 0 ? wb : wa, conv, "pesan " + i));
            }
            Collections.sort(latencies);
            long p50 = latencies.get(24);
            long p95 = latencies.get(47);
            log.info("Latensi kirim→terima lokal (ms): p50={} p95={} max={}", p50, p95, latencies.getLast());
            assertThat(p95).as("p95 kirim→terima (ms)").isLessThan(500);

            // delivered/read sampai ke pengirim sebagai msg.status
            wb.send("msg.read", "r1", "{\"conversation_id\":\"" + conv + "\",\"up_to_seq\":56}");
            var status = wa.await(f -> f.t().equals("msg.status"), WAIT);
            assertThat(status.d().get("read_up_to").asLong()).isEqualTo(56);

            // typing diteruskan ke lawan bicara saja
            wa.send("typing", "ty", "{\"conversation_id\":\"" + conv + "\"}");
            var typing = wb.await(f -> f.t().equals("typing"), WAIT);
            assertThat(typing.d().get("conversation_id").asString()).isEqualTo(conv);
        }
    }

    @Test
    void limaPesanOfflineDikirimUlangSekaliDanBerurutan() {
        var a = login(null);
        var b = login(null);
        String conv = activeConversation(a, b);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) {
            ids.add(UUID.randomUUID());
        }
        try (var wa = WsClient.connect(gateway.port(), a.token())) {
            ping(wa);
            // Pengirim "offline": pesan sempat terkirim tapi ack hilang (koneksi putus sebelum ack).
            try (var wb1 = WsClient.connect(gateway.port(), b.token())) {
                for (int i = 0; i < 5; i++) {
                    wb1.send("msg.send", "s" + i, msgSend(conv, ids.get(i), "offline " + i));
                }
            }
            // Online lagi: antrean dikirim ulang dengan client_msg_id yang sama (dua kali, untuk memastikan).
            try (var wb2 = WsClient.connect(gateway.port(), b.token())) {
                var seqs = new ArrayList<Long>();
                for (int round = 0; round < 2; round++) {
                    for (int i = 0; i < 5; i++) {
                        String fid = "r" + round + "-" + i;
                        wb2.send("msg.send", fid, msgSend(conv, ids.get(i), "offline " + i));
                        var ack = wb2.await(f -> f.t().equals("msg.ack") && fid.equals(f.json().get("id").asString()), WAIT);
                        if (round == 0) {
                            seqs.add(ack.d().get("seq").asLong());
                        } else {
                            assertThat(ack.d().get("seq").asLong()).isEqualTo(seqs.get(i));
                        }
                    }
                }
                assertThat(seqs).containsExactly(2L, 3L, 4L, 5L, 6L);
            }
            // Penerima mendapat tepat lima msg.new, berurutan, tanpa ganda.
            await().atMost(WAIT).pollDelay(Duration.ofSeconds(1)).untilAsserted(() -> { });
            var got = wa.drain().stream().filter(f -> f.t().equals("msg.new"))
                    .map(f -> f.d().get("seq").asLong()).toList();
            assertThat(got).containsExactly(2L, 3L, 4L, 5L, 6L);
        }
        assertThat(history(a, conv, 1)).extracting(n -> n.get("body").asString())
                .containsExactly("offline 0", "offline 1", "offline 2", "offline 3", "offline 4");
    }

    @Test
    void restartGatewayTanpaKehilanganPesan() {
        var a = login(null);
        var b = login(null);
        String conv = activeConversation(a, b);
        long lastSeenByB;
        try (var wa = WsClient.connect(gateway.port(), a.token()); var wb = WsClient.connect(gateway.port(), b.token())) {
            ping(wa);
            ping(wb);
            roundTrip(wa, wb, conv, "sebelum restart");
            lastSeenByB = 2;
            gateway.stop();
            // Socket ditutup server; client akan reconnect dengan backoff.
            await().atMost(WAIT).until(() -> wb.closed().isDone());
        }
        // Selama gateway mati: a mengirim lewat fallback REST.
        for (int i = 0; i < 3; i++) {
            var res = send("POST", "/v1/conversations/" + conv + "/messages",
                    "{\"client_msg_id\":\"" + UUID.randomUUID() + "\",\"type\":\"TEXT\",\"body\":\"saat mati " + i + "\"}",
                    a.token());
            assertThat(res.statusCode()).isEqualTo(201);
        }
        gateway.start();
        try (var wa = WsClient.connect(gateway.port(), a.token()); var wb = WsClient.connect(gateway.port(), b.token())) {
            ping(wa);
            ping(wb);
            // Sinkron last_seq saat reconnect → gap fill.
            var sync = json.readTree(send("POST", "/v1/conversations/sync",
                    "{\"conversations\":[{\"conversation_id\":\"" + conv + "\",\"last_seq\":" + lastSeenByB + "}]}",
                    b.token()).body());
            assertThat(sync.get("changed").get(0).get("last_seq").asLong()).isEqualTo(5);
            assertThat(history(b, conv, lastSeenByB)).extracting(n -> n.get("body").asString())
                    .containsExactly("saat mati 0", "saat mati 1", "saat mati 2");
            // Realtime jalan lagi.
            var got = roundTripFrame(wa, wb, conv, "setelah restart");
            assertThat(got.d().get("seq").asLong()).isEqualTo(6);
        }
    }

    @Test
    void penerimaOfflineMendapatPushLaluPesanLengkap() {
        var a = login(null);
        var b = login("e2e-push-" + UUID.randomUUID());
        String conv = activeConversation(a, b);
        try (var wa = WsClient.connect(gateway.port(), a.token())) {
            ping(wa);
            // b tidak terhubung.
            for (int i = 0; i < 2; i++) {
                String fid = "p" + i;
                wa.send("msg.send", fid, msgSend(conv, UUID.randomUUID(), "untuk b " + i));
                wa.await(f -> f.t().equals("msg.ack") && fid.equals(f.json().get("id").asString()), WAIT);
            }
        }
        await().atMost(WAIT).untilAsserted(() -> assertThat(pushes.recent())
                .filteredOn(p -> "DM_MESSAGE".equals(p.type()) && conv.equals(p.data().get("conversation_id")))
                .hasSize(2));
        // b membuka app → pesan lengkap.
        assertThat(history(b, conv, 1)).extracting(n -> n.get("body").asString())
                .containsExactly("untuk b 0", "untuk b 1");
    }

    // ---------------------------------------------------------------- bantu

    private long roundTrip(WsClient from, WsClient to, String conv, String body) {
        long t0 = System.nanoTime();
        var f = roundTripFrame(from, to, conv, body);
        return (f.receivedAtNanos() - t0) / 1_000_000;
    }

    private WsClient.Frame roundTripFrame(WsClient from, WsClient to, String conv, String body) {
        from.send("msg.send", UUID.randomUUID().toString(), msgSend(conv, UUID.randomUUID(), body));
        return to.await(f -> f.t().equals("msg.new") && body.equals(f.d().path("body").asString()), WAIT);
    }

    private static void ping(WsClient c) {
        c.send("ping", "p", "{}");
        c.await(f -> f.t().equals("pong"), WAIT);
    }

    private static String msgSend(String conv, UUID cmid, String body) {
        return "{\"conversation_id\":\"" + conv + "\",\"client_msg_id\":\"" + cmid + "\",\"type\":\"TEXT\",\"body\":\""
                + body + "\"}";
    }

    private List<tools.jackson.databind.JsonNode> history(User u, String conv, long afterSeq) {
        var res = send("GET", "/v1/conversations/" + conv + "/messages?after_seq=" + afterSeq, null, u.token());
        assertThat(res.statusCode()).isEqualTo(200);
        var out = new ArrayList<tools.jackson.databind.JsonNode>();
        json.readTree(res.body()).get("items").forEach(out::add);
        return out;
    }

    private User login(String pushToken) {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var res = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "ios", pushToken), null);
        String token = json.readTree(res.body()).get("tokens").get("access_token").asString();
        var profile = json.readTree(send("GET", "/v1/me/profile", null, token).body());
        return new User(token, profile.get("public_id").asString());
    }

    private String activeConversation(User author, User whisperer) {
        String originId = createPost(author.token(), "e2e");
        var conv = json.readTree(send("POST", "/v1/conversations", """
                {"origin_type":"POST","origin_id":"%s","first_message":"hai","client_msg_id":"%s"}"""
                .formatted(originId, UUID.randomUUID()), whisperer.token()).body());
        String id = conv.get("id").asString();
        assertThat(send("POST", "/v1/conversations/" + id + "/accept", null, author.token()).statusCode()).isEqualTo(200);
        return id;
    }
}
