package app.senyap.it;

import static app.senyap.support.AuthFlows.MOCK_CODE;
import static app.senyap.support.AuthFlows.randomLocalPhone;
import static app.senyap.support.AuthFlows.toE164;
import static app.senyap.support.AuthFlows.verifyBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.senyap.contracts.rt.RealtimeBus;
import app.senyap.support.AdminClient;
import app.senyap.support.IntegrationTest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fase 5 — siap produksi: propagasi trace, log tanpa data sensitif, dan pen-test ringan otomatis
 * (IDOR {@code conversations/*} + safety, enumerasi alias, timing attack pada block).
 */
class ProductionReadinessIT extends IntegrationTest {

    @Autowired
    JsonMapper json;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisConnectionFactory redisFactory;

    record User(String token, String refresh, String publicId, String phone, String alias) {
    }

    /** Semua log (pesan terformat + argumen + MDC + stack trace) selama satu test. */
    static final class Capture extends AppenderBase<ILoggingEvent> {
        final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent e) {
            var sb = new StringBuilder(e.getLoggerName()).append(' ').append(e.getFormattedMessage());
            e.getMDCPropertyMap().forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
            for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                sb.append(' ').append(t.getClassName()).append(": ").append(t.getMessage());
            }
            lines.add(sb.toString());
        }
    }

    private final Capture capture = new Capture();

    @BeforeEach
    void attachLogCapture() {
        var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        capture.setContext(root.getLoggerContext());
        capture.start();
        root.addAppender(capture);
    }

    @AfterEach
    void detachLogCapture() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(capture);
        capture.stop();
    }

    // ------------------------------------------------------------------ OpenTelemetry

    @Test
    void traceDariMsgSendSampaiFrameMsgNewMemakaiTraceIdYangSama() {
        var a = login();
        var b = login();
        String conv = activeConversation(a, b);
        String node = "it-trace-" + UUID.randomUUID();
        redis.opsForHash().put(RealtimeBus.presenceKey(UUID.fromString(b.publicId())),
                RealtimeBus.presenceField(node, "s1"), Long.toString(System.currentTimeMillis()));
        var received = new CopyOnWriteArrayList<String>();
        var container = listen(node, received);
        try {
            String traceId = randomHex(32);
            // Seolah gateway: span msg.send dengan traceparent ini memanggil REST api.
            var res = send("POST", "/v1/conversations/" + conv + "/messages",
                    "{\"client_msg_id\":\"" + UUID.randomUUID() + "\",\"type\":\"TEXT\",\"body\":\"jejak\"}",
                    a.token(), Map.of("traceparent", "00-" + traceId + "-" + randomHex(16) + "-01"));
            assertThat(res.statusCode()).as(res.body()).isEqualTo(201);

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received)
                    .anySatisfy(r -> assertThat(r).contains("\"t\":\"msg.new\"").contains("jejak")));
            JsonNode delivery = received.stream().map(json::readTree)
                    .filter(d -> "msg.new".equals(d.get("frame").get("t").asString())).findFirst().orElseThrow();
            assertThat(delivery.get("traceparent").asString())
                    .as("api → outbox → Kafka → delivery router → Redis membawa trace yang sama")
                    .startsWith("00-" + traceId + "-");
        } finally {
            container.stop();
            redis.delete(RealtimeBus.presenceKey(UUID.fromString(b.publicId())));
        }
    }

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    /** Spring Boot mematikan ekspor Prometheus di test; yang diuji: meter terdaftar dengan nama + tag benar. */
    @Test
    void metrikDashboardTerdaftar() {
        assertThat(meters.find("senyap.outbox.pending").tag("module", "chat").gauge()).isNotNull();
        assertThat(meters.find("senyap.moderation.queue.open").tag("priority", "0").gauge()).isNotNull();
        assertThat(meters.find("senyap.moderation.queue.oldest").tag("priority", "0").gauge()).isNotNull();
        assertThat(meters.find("senyap.chat.delivery.lag").timer()).isNotNull();
    }

    // ------------------------------------------------------------------ log tanpa data sensitif

    private static final Pattern MOCK_OTP = Pattern.compile("(?<!\\d)" + MOCK_CODE + "(?!\\d)");

    @Test
    void logTidakMemuatIsiPesanNomorHpOtpAtauToken() {
        var a = login();
        var b = login();
        String secretPost = "rahasia-post-" + UUID.randomUUID();
        String secretMsg = "rahasia-pesan-" + UUID.randomUUID();
        String secretReply = "rahasia-balasan-" + UUID.randomUUID();
        String post = createPost(a.token(), secretPost);
        send("POST", "/v1/posts/" + post + "/replies", "{\"body\":\"" + secretReply + "\"}", b.token());
        String conv = startWhisper(b, post);
        send("POST", "/v1/conversations/" + conv + "/accept", null, a.token());
        sendMsg(a, conv, secretMsg);
        // Jalur error juga: token salah, OTP salah, pesan ke percakapan orang lain.
        send("GET", "/v1/me/profile", null, a.token() + "x");
        send("POST", "/v1/auth/verify", verifyBody(a.phone(), "123456", "ios", null), null);
        send("POST", "/v1/auth/refresh", "{\"refresh_token\":\"" + a.refresh() + "\"}", null);
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).until(() -> true); // worker selesai

        assertThat(capture.lines).as("log tertangkap").isNotEmpty();
        for (String line : capture.lines) {
            assertThat(line).doesNotContain(secretMsg, secretReply, a.phone(), b.phone(), toE164(a.phone()),
                    toE164(b.phone()), a.token(), b.token(), a.refresh(), b.refresh());
            assertThat(MOCK_OTP.matcher(line).find()).as("kode OTP di log: %s", line).isFalse();
            assertThat(line).doesNotContainIgnoringCase("Bearer ey");
        }
    }

    // ------------------------------------------------------------------ IDOR

    @Test
    void orangLuarTidakBisaMenyentuhPercakapanOrangLainDanResponsnyaSamaDenganIdFiktif() {
        var a = login();
        var b = login();
        var outsider = login();
        String conv = activeConversation(a, b);
        sendMsg(a, conv, "untuk b saja");
        String fake = "7" + String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits())).substring(0, 17);

        for (String id : List.of(conv, fake)) {
            var calls = List.of(
                    send("GET", "/v1/conversations/" + id, null, outsider.token()),
                    send("GET", "/v1/conversations/" + id + "/messages", null, outsider.token()),
                    send("POST", "/v1/conversations/" + id + "/accept", null, outsider.token()),
                    send("POST", "/v1/conversations/" + id + "/decline", null, outsider.token()),
                    send("POST", "/v1/conversations/" + id + "/block", null, outsider.token()),
                    send("POST", "/v1/conversations/" + id + "/hide", null, outsider.token()),
                    send("POST", "/v1/conversations/" + id + "/leave", null, outsider.token()),
                    send("POST", "/v1/conversations/" + id + "/receipts", "{\"read_up_to\":2}", outsider.token()),
                    send("DELETE", "/v1/conversations/" + id + "/messages/2", null, outsider.token()),
                    sendMsg(outsider, id, "nyusup"),
                    send("POST", "/v1/reports", "{\"target_type\":\"CONVERSATION\",\"target_id\":\"" + id
                            + "\",\"reason\":\"spam\"}", outsider.token()),
                    send("POST", "/v1/reports", "{\"target_type\":\"MESSAGE\",\"target_id\":\"" + id
                            + "\",\"seq\":2,\"reason\":\"spam\"}", outsider.token()),
                    send("POST", "/v1/blocks", "{\"target_type\":\"CONVERSATION\",\"target_id\":\"" + id + "\"}",
                            outsider.token()));
            for (var r : calls) {
                assertThat(r.statusCode()).as("%s %s → %s", r.request().method(), r.uri(), r.body()).isEqualTo(404);
                assertThat(code(r)).isEqualTo("NOT_FOUND");
                assertThat(r.body()).doesNotContain("untuk b saja");
            }
        }
        var sync = send("POST", "/v1/conversations/sync",
                "{\"conversations\":[{\"conversation_id\":\"" + conv + "\",\"last_seq\":0}]}", outsider.token());
        assertThat(sync.body()).doesNotContain(conv);
        // Percakapan tetap utuh untuk pemiliknya.
        var detail = send("GET", "/v1/conversations/" + conv + "/messages", null, b.token());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("untuk b saja");
        assertThat(send("GET", "/v1/me/blocks", null, outsider.token()).body()).contains("\"items\":[]");
    }

    @Test
    void blokirMilikOrangLainTidakBisaDibukaDanKonsolTidakMenerimaTokenUser() {
        var a = login();
        var b = login();
        var outsider = login();
        String post = createPost(b.token(), "post b");
        assertThat(send("POST", "/v1/blocks", "{\"target_type\":\"POST\",\"target_id\":\"" + post + "\"}", a.token())
                .statusCode()).isBetween(200, 204);
        String blockId = json.readTree(send("GET", "/v1/me/blocks", null, a.token()).body())
                .get("items").get(0).get("id").asString();

        var r = send("DELETE", "/v1/me/blocks/" + blockId, null, outsider.token());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json.readTree(send("GET", "/v1/me/blocks", null, a.token()).body()).get("items").size()).isOne();

        // Konsol moderator: access token app tidak berlaku; tanpa sesi admin → login, bukan data.
        for (String path : List.of("/admin", "/admin/audit", "/admin/cases/1", "/admin/legal/cases/1/identity")) {
            var res = send("GET", path, null, a.token());
            assertThat(res.statusCode()).as(path).isIn(302, 401, 403);
            assertThat(res.body()).doesNotContain("post b");
        }
        // Moderator tanpa peran legal tidak bisa menelusuri identitas (POST juga).
        var mod = new AdminClient(port).login("mod-pentest", "MODERATOR");
        assertThat(mod.post("/admin/legal/cases/1/identity", Map.of("reason", "coba")).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ enumerasi alias

    @Test
    void aliasTidakBisaDipakaiUntukMenemukanAtauMemilihAkun() {
        var a = login();
        var b = login();
        for (String path : List.of("/v1/users/" + b.publicId(), "/v1/profiles/" + b.publicId(),
                "/v1/aliases/" + enc(b.alias()), "/v1/aliases?name=" + enc(b.alias()),
                "/v1/me/profile?public_id=" + b.publicId())) {
            var r = send("GET", path, null, a.token());
            assertThat(r.body()).as(path).doesNotContain(b.phone()).doesNotContain(toE164(b.phone()));
            if (!path.startsWith("/v1/me/profile")) {
                assertThat(r.statusCode()).as(path).isEqualTo(404);
                assertThat(r.body()).as(path).doesNotContain(b.alias());
            } else {
                assertThat(r.body()).as("parameter public_id diabaikan").doesNotContain(b.publicId());
            }
        }
        // Alias hanya bisa dipakai lewat token saran dari server (tidak bisa "mencoba" alias orang lain).
        var forged = send("PUT", "/v1/me/alias", "{\"suggestion_token\":\"" + enc(b.alias()) + "\"}", a.token());
        assertThat(forged.statusCode()).isBetween(400, 404);
        assertThat(forged.body()).doesNotContain(b.alias()).doesNotContainIgnoringCase("sudah dipakai");
        // Saran alias acak tidak pernah mengembalikan alias milik akun lain.
        for (int i = 0; i < 10; i++) {
            var s = send("POST", "/v1/me/alias/suggest", "{}", a.token());
            if (s.statusCode() == 429) {
                break; // dibatasi rate limit: memang yang diharapkan untuk mencegah pemindaian massal
            }
            assertThat(s.body()).doesNotContain(b.alias());
        }
    }

    // ------------------------------------------------------------------ timing attack pada block

    @Test
    void responsBlockTidakBisaDibedakanDariKontenYangTidakAdaBaikIsiMaupunWaktunya() {
        var a = login();
        var blocked = login();
        String post = createPost(a.token(), "post a");
        String theirs = createPost(blocked.token(), "post yang diblokir");
        assertThat(send("POST", "/v1/blocks", "{\"target_type\":\"POST\",\"target_id\":\"" + theirs + "\"}", a.token())
                .statusCode()).isBetween(200, 204);
        String fakePost = Long.toString(Long.parseLong(post) + 7919);

        // Isi: identik selain request_id.
        for (String path : List.of("/v1/posts/", "/v1/posts/%s/replies")) {
            var real = send("GET", path.contains("%s") ? path.formatted(post) : path + post, null, blocked.token());
            var none = send("GET", path.contains("%s") ? path.formatted(fakePost) : path + fakePost, null, blocked.token());
            assertThat(real.statusCode()).as(path).isEqualTo(none.statusCode()).isEqualTo(404);
            assertThat(stripRequestId(real.body())).isEqualTo(stripRequestId(none.body()));
        }
        var bisikReal = startWhisperRes(blocked, post);
        var bisikNone = startWhisperRes(blocked, fakePost);
        assertThat(bisikReal.statusCode()).isEqualTo(bisikNone.statusCode()).isEqualTo(404);
        assertThat(stripRequestId(bisikReal.body())).isEqualTo(stripRequestId(bisikNone.body()));

        // Waktu: median selisih kecil dibanding jitter jaringan (ambang longgar supaya tidak flaky di CI).
        for (int i = 0; i < 5; i++) { // pemanasan JIT + cache
            send("GET", "/v1/posts/" + post, null, blocked.token());
            send("GET", "/v1/posts/" + fakePost, null, blocked.token());
        }
        var tReal = new ArrayList<Long>();
        var tNone = new ArrayList<Long>();
        for (int i = 0; i < 30; i++) {
            tReal.add(time(() -> send("GET", "/v1/posts/" + post, null, blocked.token())));
            tNone.add(time(() -> send("GET", "/v1/posts/" + fakePost, null, blocked.token())));
        }
        long diffMs = Math.abs(median(tReal) - median(tNone)) / 1_000_000;
        assertThat(diffMs).as("median blocked %d µs vs tidak ada %d µs", median(tReal) / 1000, median(tNone) / 1000)
                .isLessThan(15);
    }

    // ------------------------------------------------------------------ helper

    private User login() {
        String phone = randomLocalPhone();
        send("POST", "/v1/auth/otp", "{\"phone\":\"" + phone + "\"}", null);
        var res = send("POST", "/v1/auth/verify", verifyBody(phone, MOCK_CODE, "android", null), null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        var tokens = json.readTree(res.body()).get("tokens");
        String token = tokens.get("access_token").asString();
        var suggestion = json.readTree(send("POST", "/v1/me/alias/suggest", "{}", token).body());
        send("PUT", "/v1/me/alias", "{\"suggestion_token\":\"" + suggestion.get("suggestion_token").asString() + "\"}",
                token);
        var profile = json.readTree(send("GET", "/v1/me/profile", null, token).body());
        var alias = profile.get("alias");
        return new User(token, tokens.get("refresh_token").asString(), profile.get("public_id").asString(), phone,
                alias.get("name").asString() + " #" + alias.get("tag").asString());
    }

    private HttpResponse<String> startWhisperRes(User u, String postId) {
        return send("POST", "/v1/conversations", """
                {"origin_type":"POST","origin_id":"%s","first_message":"hai","client_msg_id":"%s"}"""
                .formatted(postId, UUID.randomUUID()), u.token());
    }

    private String startWhisper(User u, String postId) {
        var res = startWhisperRes(u, postId);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return json.readTree(res.body()).get("id").asString();
    }

    private String activeConversation(User a, User b) {
        String id = startWhisper(b, createPost(a.token(), "post"));
        var res = send("POST", "/v1/conversations/" + id + "/accept", null, a.token());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return id;
    }

    private HttpResponse<String> sendMsg(User u, String id, String body) {
        return send("POST", "/v1/conversations/" + id + "/messages",
                "{\"client_msg_id\":\"" + UUID.randomUUID() + "\",\"type\":\"TEXT\",\"body\":\"" + body + "\"}", u.token());
    }

    private RedisMessageListenerContainer listen(String node, List<String> into) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisFactory);
        container.addMessageListener((m, p) -> into.add(new String(m.getBody())),
                new ChannelTopic(RealtimeBus.nodeChannel(node)));
        container.afterPropertiesSet();
        container.start();
        await().atMost(Duration.ofSeconds(5)).until(container::isListening);
        return container;
    }

    private String code(HttpResponse<String> res) {
        return json.readTree(res.body()).get("error").get("code").asString();
    }

    private static String stripRequestId(String body) {
        return body.replaceAll("\"request_id\"\\s*:\\s*\"[^\"]*\"", "\"request_id\":\"-\"");
    }

    private static long time(Runnable r) {
        long t = System.nanoTime();
        r.run();
        return System.nanoTime() - t;
    }

    private static long median(List<Long> xs) {
        var s = xs.stream().sorted().toList();
        return s.get(s.size() / 2);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String randomHex(int n) {
        var sb = new StringBuilder();
        var r = new java.util.Random();
        while (sb.length() < n) {
            sb.append(Integer.toHexString(r.nextInt(16)));
        }
        return sb.toString();
    }
}
