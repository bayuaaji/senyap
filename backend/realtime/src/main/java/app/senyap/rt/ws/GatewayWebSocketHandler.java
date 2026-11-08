package app.senyap.rt.ws;

import app.senyap.contracts.ws.FrameType;
import app.senyap.contracts.ws.WsFrame;
import app.senyap.rt.auth.AccessTokenVerifier;
import app.senyap.rt.auth.HandshakeAuthFilter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Endpoint {@code /v1/ws} (functional spec FS-05 §5.5). Frame masuk diproses <b>berurutan per sesi</b>
 * ({@code concatMap}), jadi urutan {@code msg.send} dari satu device sama dengan urutan {@code seq}.
 *
 * <ul>
 *   <li>{@code msg.send} → {@code POST /v1/conversations/{id}/messages} → {@code msg.ack} (atau {@code error})</li>
 *   <li>{@code msg.delivered} / {@code msg.read} → {@code POST …/receipts}; api mengirim {@code msg.status} ke lawan</li>
 *   <li>{@code typing} → langsung ke node lawan bicara lewat Redis (hanya di memori, throttle 1 per 3 detik)</li>
 *   <li>{@code ping} → {@code pong} + perbarui presence</li>
 * </ul>
 * Isi frame tidak pernah ditulis ke log. Token kedaluwarsa → {@code error UNAUTHENTICATED} + tutup 4401.
 */
@Component
class GatewayWebSocketHandler implements WebSocketHandler {

    static final long TYPING_THROTTLE_MS = 3000;
    static final long PEER_CACHE_MS = 60_000;

    private final JsonMapper json;
    private final RealtimeNode node;
    private final ChatApiClient api;
    private final AccessTokenVerifier verifier;

    GatewayWebSocketHandler(JsonMapper json, RealtimeNode node, ChatApiClient api, AccessTokenVerifier verifier) {
        this.json = json;
        this.node = node;
        this.api = api;
        this.verifier = verifier;
    }

    /**
     * Handshake sudah ditolak {@link HandshakeAuthFilter} bila token tidak valid. Di sini token dibaca lagi dari
     * header handshake dan diverifikasi (JWKS di-cache) karena atribut exchange tidak selalu tersalin ke sesi.
     */
    @Override
    public Mono<Void> handle(WebSocketSession ws) {
        String header = ws.getHandshakeInfo().getHeaders().getFirst(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return ws.close(CloseStatus.POLICY_VIOLATION);
        }
        String token = header.substring(7).trim();
        return Mono.fromCallable(() -> verifier.verify(token))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(p -> p.isPresent() ? session(ws, p.get(), token)
                        : ws.close(new CloseStatus(FrameType.CLOSE_UNAUTHENTICATED, "token")));
    }

    private Mono<Void> session(WebSocketSession ws, AccessTokenVerifier.Principal principal, String token) {
        var s = new ClientSession(ws.getId(), principal.publicId(), token);

        var inbound = ws.receive()
                .filter(m -> m.getType() == WebSocketMessage.Type.TEXT)
                .map(WebSocketMessage::getPayloadAsText)
                .concatMap(text -> {
                    s.lastInboundAt = System.currentTimeMillis();
                    return handleFrame(s, text).onErrorResume(e -> {
                        s.emit(error(null, "INTERNAL", "Terjadi kesalahan. Coba lagi nanti."));
                        return Mono.empty();
                    });
                })
                .then();
        var outbound = ws.send(s.outbound.asFlux().map(f -> ws.textMessage(json.writeValueAsString(f))));

        return node.register(s)
                .then(Mono.firstWithSignal(inbound, outbound))
                .doFinally(sig -> {
                    s.complete();
                    node.unregister(s).subscribe();
                })
                .then(Mono.defer(() -> ws.isOpen() && s.closeStatus != null ? ws.close(s.closeStatus) : Mono.empty()));
    }

    Mono<Void> handleFrame(ClientSession s, String text) {
        WsFrame in;
        try {
            in = json.readValue(text, WsFrame.class);
        } catch (JacksonException e) {
            s.emit(error(null, "VALIDATION_FAILED", "Frame tidak valid."));
            return Mono.empty();
        }
        if (in.t() == null) {
            s.emit(error(in.id(), "VALIDATION_FAILED", "Frame tidak valid."));
            return Mono.empty();
        }
        return switch (in.t()) {
            case FrameType.PING -> {
                s.emit(WsFrame.of(FrameType.PONG, in.id(), Map.of()));
                yield node.touch(s);
            }
            case FrameType.MSG_SEND -> send(s, in);
            case FrameType.MSG_DELIVERED -> receipts(s, in, "delivered_up_to");
            case FrameType.MSG_READ -> receipts(s, in, "read_up_to");
            case FrameType.TYPING -> typing(s, in);
            default -> {
                s.emit(error(in.id(), "VALIDATION_FAILED", "Tipe frame tidak dikenal."));
                yield Mono.empty();
            }
        };
    }

    private Mono<Void> send(ClientSession s, WsFrame in) {
        String conv = conversationId(in);
        if (conv == null) {
            s.emit(error(in.id(), "VALIDATION_FAILED", "conversation_id wajib."));
            return Mono.empty();
        }
        var body = new HashMap<String, Object>();
        for (String k : new String[] {"client_msg_id", "type", "body", "media_id"}) {
            Object v = in.d().get(k);
            if (v != null) {
                body.put(k, v);
            }
        }
        return api.post(s.token, "/v1/conversations/" + conv + "/messages", body).doOnNext(r -> {
            if (r.ok()) {
                var d = new LinkedHashMap<String, Object>();
                d.put("client_msg_id", r.body().get("client_msg_id").asString());
                d.put("conversation_id", r.body().get("conversation_id").asString());
                d.put("seq", r.body().get("seq").asLong());
                d.put("created_at", r.body().get("created_at").asString());
                s.emit(WsFrame.of(FrameType.MSG_ACK, in.id(), d));
            } else {
                apiError(s, in, r);
            }
        }).then();
    }

    private Mono<Void> receipts(ClientSession s, WsFrame in, String field) {
        String conv = conversationId(in);
        Object upTo = in.d().get("up_to_seq");
        if (conv == null || !(upTo instanceof Number n)) {
            s.emit(error(in.id(), "VALIDATION_FAILED", "conversation_id dan up_to_seq wajib."));
            return Mono.empty();
        }
        return api.post(s.token, "/v1/conversations/" + conv + "/receipts", Map.of(field, n.longValue()))
                .doOnNext(r -> {
                    if (!r.ok()) {
                        apiError(s, in, r);
                    }
                }).then();
    }

    private Mono<Void> typing(ClientSession s, WsFrame in) {
        String conv = conversationId(in);
        if (conv == null) {
            return Mono.empty();
        }
        long now = System.currentTimeMillis();
        Long last = s.typingSentAt.get(conv);
        if (last != null && now - last < TYPING_THROTTLE_MS) {
            return Mono.empty();
        }
        s.typingSentAt.put(conv, now);
        return peer(s, conv).flatMap(p -> p.active()
                ? node.deliver(p.publicId(), WsFrame.of(FrameType.TYPING, null, Map.of("conversation_id", conv)))
                : Mono.empty());
    }

    /** Lawan bicara percakapan (dan apakah masih ACTIVE), dari {@code GET /v1/conversations/{id}}, di-cache 60 dtk. */
    private Mono<ClientSession.Peer> peer(ClientSession s, String conv) {
        var cached = s.peers.get(conv);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < PEER_CACHE_MS) {
            return Mono.just(cached);
        }
        return api.get(s.token, "/v1/conversations/" + conv).flatMap(r -> {
            if (!r.ok() || r.body().get("peer").get("public_id") == null
                    || r.body().get("peer").get("public_id").isNull()) {
                return Mono.empty();
            }
            var p = new ClientSession.Peer(UUID.fromString(r.body().get("peer").get("public_id").asString()),
                    "ACTIVE".equals(r.body().get("status").asString()), System.currentTimeMillis());
            s.peers.put(conv, p);
            return Mono.just(p);
        });
    }

    private void apiError(ClientSession s, WsFrame in, ChatApiClient.Result r) {
        s.emit(error(in.id(), r.errorCode(), r.errorMessage()));
        if (r.status() == 401) {
            s.closeStatus = new CloseStatus(FrameType.CLOSE_UNAUTHENTICATED, "token");
            s.complete();
        }
    }

    private static String conversationId(WsFrame in) {
        Object v = in.d().get("conversation_id");
        return v == null ? null : v.toString().matches("\\d{1,19}") ? v.toString() : null;
    }

    static WsFrame error(String refId, String code, String message) {
        var d = new HashMap<String, Object>();
        if (refId != null) {
            d.put("ref_id", refId);
        }
        d.put("code", code);
        d.put("message", message);
        return WsFrame.of(FrameType.ERROR, null, d);
    }
}
