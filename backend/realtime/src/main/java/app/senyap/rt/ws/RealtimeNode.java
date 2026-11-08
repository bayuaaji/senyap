package app.senyap.rt.ws;

import app.senyap.contracts.rt.RealtimeBus;
import app.senyap.contracts.ws.FrameType;
import app.senyap.contracts.ws.WsFrame;
import app.senyap.rt.obs.Traces;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Node gateway ini: daftar sesi lokal per {@code public_id}, presence di Redis, dan langganan channel node
 * ({@link RealtimeBus}). Frame dari delivery router (api) atau dari node lain (typing) masuk lewat channel
 * {@code rt:node:{node_id}} lalu diteruskan ke semua sesi milik penerima di node ini.
 */
@Component
class RealtimeNode implements SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RealtimeNode.class);
    static final CloseStatus SERVICE_RESTART = new CloseStatus(FrameType.CLOSE_SERVICE_RESTART, "restart");

    private final ReactiveStringRedisTemplate redis;
    private final JsonMapper json;
    private final String nodeId;
    private final Duration idleTimeout;
    private final Map<UUID, Set<ClientSession>> sessions = new ConcurrentHashMap<>();
    private final Duration drainSpread;
    private final Duration drainTimeout;
    private volatile Disposable subscription;
    private volatile boolean running;
    private volatile boolean draining;
    private final Traces traces;

    RealtimeNode(ReactiveStringRedisTemplate redis, JsonMapper json,
                 @Value("${senyap.rt.node-id:}") String nodeId,
                 @Value("${senyap.rt.idle-timeout:75s}") Duration idleTimeout,
                 @Value("${senyap.rt.drain-spread:10s}") Duration drainSpread,
                 @Value("${senyap.rt.drain-timeout:20s}") Duration drainTimeout,
                 Traces traces, MeterRegistry meters) {
        this.traces = traces;
        Gauge.builder("senyap.rt.connections", this, RealtimeNode::sessionCount)
                .description("Sesi WebSocket aktif di node ini").register(meters);
        this.redis = redis;
        this.json = json;
        this.nodeId = nodeId == null || nodeId.isBlank() ? UUID.randomUUID().toString() : nodeId;
        this.idleTimeout = idleTimeout;
        this.drainSpread = drainSpread;
        this.drainTimeout = drainTimeout;
    }

    /** {@code true} sejak shutdown dimulai: handshake baru langsung ditutup 1012 supaya client pindah node. */
    boolean draining() {
        return draining;
    }

    String nodeId() {
        return nodeId;
    }

    // ---------------------------------------------------------------- sesi + presence

    Mono<Void> register(ClientSession s) {
        sessions.computeIfAbsent(s.publicId, k -> ConcurrentHashMap.newKeySet()).add(s);
        return touch(s);
    }

    /** Heartbeat: perbarui presence ({@code HSET} + {@code EXPIRE}). */
    Mono<Void> touch(ClientSession s) {
        String key = RealtimeBus.presenceKey(s.publicId);
        return redis.opsForHash().put(key, RealtimeBus.presenceField(nodeId, s.id),
                        Long.toString(System.currentTimeMillis()))
                .then(redis.expire(key, RealtimeBus.PRESENCE_TTL))
                .then()
                .onErrorResume(e -> {
                    log.warn("Presence gagal diperbarui: {}", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    Mono<Void> unregister(ClientSession s) {
        var set = sessions.get(s.publicId);
        if (set != null) {
            set.remove(s);
            if (set.isEmpty()) {
                sessions.remove(s.publicId, set);
            }
        }
        return redis.opsForHash().remove(RealtimeBus.presenceKey(s.publicId), RealtimeBus.presenceField(nodeId, s.id))
                .then()
                .onErrorResume(e -> Mono.empty());
    }

    int sessionCount() {
        return sessions.values().stream().mapToInt(Set::size).sum();
    }

    /** Segarkan presence semua sesi dan tutup sesi yang diam lebih lama dari idle timeout. */
    @Scheduled(fixedDelayString = "${senyap.rt.presence-refresh:20s}")
    void refresh() {
        long cutoff = System.currentTimeMillis() - idleTimeout.toMillis();
        for (var set : sessions.values()) {
            for (var s : set) {
                if (s.lastInboundAt < cutoff) {
                    s.complete();
                } else {
                    touch(s).subscribe();
                }
            }
        }
    }

    // ---------------------------------------------------------------- pengiriman

    /** Kirim ke sesi lokal milik {@code publicId}. */
    void deliverLocal(UUID publicId, WsFrame frame) {
        var set = sessions.get(publicId);
        if (set != null) {
            set.forEach(s -> s.emit(frame));
        }
    }

    /** Kirim ke semua sesi {@code publicId} di node mana pun (lewat presence + channel node). */
    Mono<Void> deliver(UUID publicId, WsFrame frame) {
        String payload = json.writeValueAsString(new RealtimeBus.Delivery(publicId.toString(), frame));
        long now = System.currentTimeMillis();
        return redis.opsForHash().entries(RealtimeBus.presenceKey(publicId))
                .filter(e -> {
                    try {
                        return now - Long.parseLong(e.getValue().toString()) <= RealtimeBus.PRESENCE_TTL.toMillis();
                    } catch (NumberFormatException ex) {
                        return false;
                    }
                })
                .map(e -> RealtimeBus.nodeOf(e.getKey().toString()))
                .filter(n -> n != null)
                .distinct()
                .flatMap(node -> redis.convertAndSend(RealtimeBus.nodeChannel(node), payload))
                .then()
                .onErrorResume(e -> Mono.empty());
    }

    private void onBusMessage(String payload) {
        try {
            var d = json.readValue(payload, RealtimeBus.Delivery.class);
            traces.inSpan("msg.new", SpanKind.PRODUCER, d.traceparent(), Map.of("senyap.frame", d.frame().t()), () -> {
                deliverLocal(UUID.fromString(d.to()), d.frame());
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Pesan bus tidak valid: {}", e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void start() {
        running = true;
        subscription = Flux.defer(() -> redis.listenToChannel(RealtimeBus.nodeChannel(nodeId)))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(5)))
                .subscribe(m -> onBusMessage(m.getMessage()));
        log.info("Gateway node {} aktif", nodeId);
    }

    /**
     * Graceful drain saat deploy (dipanggil sebelum web server berhenti, lihat {@link #getPhase()}):
     * <ol>
     *   <li>tandai draining → handshake baru ditolak 1012 (readiness juga sudah {@code REFUSING_TRAFFIC});</li>
     *   <li>tutup sesi dengan kode {@link FrameType#CLOSE_SERVICE_RESTART}, <b>disebar</b> merata selama
     *       {@code senyap.rt.drain-spread} supaya client tidak reconnect serentak ke node lain;</li>
     *   <li>tunggu semua sesi lepas (maks. {@code senyap.rt.drain-timeout}), lalu hapus sisa presence.</li>
     * </ol>
     * Frame yang datang selama drain tetap dikirim ke sesi yang belum ditutup; yang terlewat ditutup sinkron
     * {@code last_seq} saat client tersambung lagi.
     */
    @Override
    public void stop() {
        draining = true;
        var all = new ArrayList<ClientSession>();
        sessions.values().forEach(all::addAll);
        log.info("Gateway node {} drain: {} sesi", nodeId, all.size());
        long spreadMs = drainSpread.toMillis();
        long start = System.currentTimeMillis();
        for (int i = 0; i < all.size(); i++) {
            long due = start + (all.size() <= 1 ? 0 : spreadMs * i / (all.size() - 1));
            sleepUntil(due);
            var s = all.get(i);
            s.close(SERVICE_RESTART);
        }
        long deadline = System.currentTimeMillis() + drainTimeout.toMillis();
        while (sessionCount() > 0 && System.currentTimeMillis() < deadline) {
            sleepUntil(System.currentTimeMillis() + 50);
        }
        // Sisa (sesi macet): hapus presence supaya router memakai push, lalu tutup paksa.
        var left = new ArrayList<ClientSession>();
        sessions.values().forEach(left::addAll);
        Flux.fromIterable(left).flatMap(this::unregister).then().block(Duration.ofSeconds(3));
        left.forEach(ClientSession::complete);
        if (subscription != null) {
            subscription.dispose();
        }
        running = false;
        log.info("Gateway node {} drain selesai dalam {} ms ({} sesi dipaksa)", nodeId,
                System.currentTimeMillis() - start, left.size());
    }

    private static void sleepUntil(long epochMs) {
        long wait = epochMs - System.currentTimeMillis();
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Berhenti sebelum web server (graceful shutdown) supaya socket ditutup dengan rapi.
        return Integer.MAX_VALUE - 10;
    }

    @Override
    public void destroy() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    List<UUID> connectedUsers() {
        return List.copyOf(sessions.keySet());
    }
}
