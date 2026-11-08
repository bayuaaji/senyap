package app.senyap.rt.ws;

import app.senyap.contracts.ws.WsFrame;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
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
    /** Diisi bila sesi harus ditutup dengan kode khusus (4401 token kedaluwarsa, 1012 node drain). */
    volatile CloseStatus closeStatus;
    final Map<String, Long> typingSentAt = new ConcurrentHashMap<>();
    final Map<String, Peer> peers = new ConcurrentHashMap<>();

    /** Socket aslinya; {@code null} di unit test. */
    private final WebSocketSession ws;

    ClientSession(String id, UUID publicId, String token) {
        this(id, publicId, token, null);
    }

    ClientSession(String id, UUID publicId, String token, WebSocketSession ws) {
        this.id = id;
        this.publicId = publicId;
        this.token = token;
        this.ws = ws;
    }

    /**
     * Tutup dengan kode tertentu (4401, 1012). Close frame dikirim langsung ke socket; hanya menyelesaikan
     * outbound membuat Reactor Netty menutup tanpa kode (1005), sehingga client tidak tahu harus berbuat apa.
     */
    void close(CloseStatus status) {
        closeStatus = status;
        if (ws != null && ws.isOpen()) {
            ws.close(status).subscribe(v -> { }, e -> { });
        }
        complete();
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
