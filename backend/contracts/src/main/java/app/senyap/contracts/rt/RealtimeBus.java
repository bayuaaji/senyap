package app.senyap.contracts.rt;

import app.senyap.contracts.ws.WsFrame;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.UUID;

/**
 * Kontrak Redis antara api (delivery router) dan gateway realtime (build guide §1.2 langkah 6).
 *
 * <ul>
 *   <li><b>Presence</b>: hash {@code presence:{public_id}}, field {@code {node_id}|{session_id}}, nilai = epoch ms
 *       heartbeat terakhir. Key di-EXPIRE {@link #PRESENCE_TTL} setiap heartbeat; field yang lebih tua dari TTL
 *       dianggap mati. Dikunci {@code public_id} (bukan user ID) karena gateway hanya tahu {@code public_id}
 *       (klaim {@code sub} access token). Satu user bisa punya beberapa sesi (device).</li>
 *   <li><b>Node channel</b>: tiap gateway subscribe {@code rt:node:{node_id}}; pesan = {@link Delivery} JSON.</li>
 * </ul>
 */
public final class RealtimeBus {

    public static final Duration PRESENCE_TTL = Duration.ofSeconds(60);

    private RealtimeBus() {
    }

    public static String presenceKey(UUID publicId) {
        return "presence:" + publicId;
    }

    public static String presenceField(String nodeId, String sessionId) {
        return nodeId + "|" + sessionId;
    }

    /** Node dari field presence, atau {@code null} bila formatnya tidak dikenal. */
    public static String nodeOf(String presenceField) {
        int i = presenceField.indexOf('|');
        return i <= 0 ? null : presenceField.substring(0, i);
    }

    public static String nodeChannel(String nodeId) {
        return "rt:node:" + nodeId;
    }

    /**
     * Satu frame untuk semua sesi milik {@code to} ({@code public_id}) di node penerima. {@code traceparent}
     * (opsional) menyambung trace {@code msg.send} → {@code msg.new} (OpenTelemetry).
     */
    public record Delivery(@JsonProperty("to") String to, @JsonProperty("frame") WsFrame frame,
                           @JsonProperty("traceparent") @JsonInclude(JsonInclude.Include.NON_NULL) String traceparent) {

        public Delivery(String to, WsFrame frame) {
            this(to, frame, null);
        }
    }
}
