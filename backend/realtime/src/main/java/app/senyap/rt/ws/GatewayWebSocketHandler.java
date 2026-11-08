package app.senyap.rt.ws;

import app.senyap.contracts.ws.FrameType;
import app.senyap.contracts.ws.WsFrame;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Endpoint {@code /v1/ws}. Fase 0: hanya heartbeat ({@code ping} → {@code pong}); frame lain dijawab
 * {@code error} {@code NOT_IMPLEMENTED}. Autentikasi token, routing pesan, dan presence menyusul di Fase 2.
 * Isi frame tidak pernah ditulis ke log (bisa berisi pesan DM).
 */
@Component
class GatewayWebSocketHandler implements WebSocketHandler {

    private final JsonMapper json;

    GatewayWebSocketHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        var replies = session.receive()
                .filter(m -> m.getType() == WebSocketMessage.Type.TEXT)
                .map(WebSocketMessage::getPayloadAsText)
                .map(this::reply)
                .map(frame -> session.textMessage(json.writeValueAsString(frame)));
        return session.send(replies);
    }

    WsFrame reply(String text) {
        WsFrame in;
        try {
            in = json.readValue(text, WsFrame.class);
        } catch (JacksonException e) {
            return error(null, "VALIDATION_FAILED", "Frame tidak valid.");
        }
        if (in.t() == null) {
            return error(in.id(), "VALIDATION_FAILED", "Frame tidak valid.");
        }
        if (FrameType.PING.equals(in.t())) {
            return WsFrame.of(FrameType.PONG, in.id(), Map.of());
        }
        return error(in.id(), "NOT_IMPLEMENTED", "Belum didukung.");
    }

    private static WsFrame error(String refId, String code, String message) {
        var d = new java.util.HashMap<String, Object>();
        if (refId != null) {
            d.put("ref_id", refId);
        }
        d.put("code", code);
        d.put("message", message);
        return WsFrame.of(FrameType.ERROR, null, d);
    }
}
