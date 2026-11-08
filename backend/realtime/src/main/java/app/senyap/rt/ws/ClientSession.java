package app.senyap.rt.ws;

import app.senyap.contracts.ws.WsFrame;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Sinks;

/** Satu koneksi WebSocket di node ini. Token hanya di memori, tidak pernah ditulis ke log. */
final class ClientSession {

    /** Lawan bicara satu percakapan (untuk {@code typing}), di-cache per sesi. */
    record Peer(UUID publicId, boolean active, long fetchedAt) {
    }

    final String id;
    final UUID publicId;
    final String token;
    final Sinks.Many<WsFrame> outbound = Sinks.many().unicast().onBackpressureBuffer();
    volatile long lastInboundAt = System.currentTimeMillis();
    /** Diisi bila sesi harus ditutup dengan kode khusus (misalnya 4401 token kedaluwarsa). */
    volatile org.springframework.web.reactive.socket.CloseStatus closeStatus;
    final Map<String, Long> typingSentAt = new ConcurrentHashMap<>();
    final Map<String, Peer> peers = new ConcurrentHashMap<>();

    ClientSession(String id, UUID publicId, String token) {
        this.id = id;
        this.publicId = publicId;
        this.token = token;
    }

    /** Kirim frame ke socket ini (aman dipanggil dari thread mana pun). */
    void emit(WsFrame frame) {
        synchronized (outbound) {
            outbound.tryEmitNext(frame);
        }
    }

    void complete() {
        synchronized (outbound) {
            outbound.tryEmitComplete();
        }
    }
}
