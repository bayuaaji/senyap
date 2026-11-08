package app.senyap.contracts.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Satu frame WebSocket: {@code {"t": "<type>", "id": "<frame id>", "d": {…}}} (functional spec FS-05 §5.5).
 *
 * @param t  tipe frame, lihat {@link FrameType}
 * @param id ID frame dari pengirim (dipakai sebagai {@code ref_id} di frame {@code error})
 * @param d  isi frame
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WsFrame(String t, String id, Map<String, Object> d) {

    public WsFrame {
        d = d == null ? Map.of() : Map.copyOf(d);
    }

    public static WsFrame of(String type, String id, Map<String, Object> data) {
        return new WsFrame(type, id, data);
    }
}
