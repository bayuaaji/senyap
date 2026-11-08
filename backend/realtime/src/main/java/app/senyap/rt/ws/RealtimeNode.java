package app.senyap.rt.ws;

import app.senyap.contracts.rt.RealtimeBus;
import app.senyap.contracts.ws.WsFrame;
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

    private final ReactiveStringRedisTemplate redis;
    private final JsonMapper json;
    private final String nodeId;
    private final Duration idleTimeout;
    private final Map<UUID, Set<ClientSession>> sessions = new ConcurrentHashMap<>();
    private volatile Disposable subscription;
    private volatile boolean running;

    RealtimeNode(ReactiveStringRedisTemplate redis, JsonMapper json,
                 @Value("${senyap.rt.node-id:}") String nodeId,
                 @Value("${senyap.rt.idle-timeout:75s}") Duration idleTimeout) {
        this.redis = redis;
        this.json = json;
        this.nodeId = nodeId == null || nodeId.isBlank() ? UUID.randomUUID().toString() : nodeId;
        this.idleTimeout = idleTimeout;
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
            deliverLocal(UUID.fromString(d.to()), d.frame());
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

    @Override
    public void stop() {
        running = false;
        // Graceful: hapus presence semua sesi supaya router langsung memakai push, lalu tutup socket.
        var all = new ArrayList<ClientSession>();
        sessions.values().forEach(all::addAll);
        Flux.fromIterable(all).flatMap(this::unregister).then().block(Duration.ofSeconds(3));
        all.forEach(ClientSession::complete);
        if (subscription != null) {
            subscription.dispose();
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
