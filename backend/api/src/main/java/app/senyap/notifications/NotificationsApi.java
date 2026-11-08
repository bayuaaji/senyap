package app.senyap.notifications;

import java.util.List;
import java.util.Map;

/** API publik modul notifications (functional spec FS-06). Fase 2: push saja. */
public interface NotificationsApi {

    /**
     * Mengirim push ke semua device aktif tiap penerima. Best effort: kegagalan pengiriman dicatat,
     * tidak dilempar. Payload tidak boleh memuat ID internal atau isi pesan (default tanpa pratinjau).
     */
    void push(List<Push> pushes);

    /**
     * @param recipientUserId penerima (hanya untuk server)
     * @param type            {@code DM_REQUEST}, {@code DM_MESSAGE}, {@code DM_ACCEPTED}, …
     * @param collapseKey     push berturut-turut dengan kunci sama digabung ({@code collapse_key} FCM /
     *                        {@code apns-collapse-id}), misalnya per percakapan
     * @param data            data untuk membuka layar yang tepat, hanya ID publik (misalnya {@code conversation_id})
     */
    record Push(long recipientUserId, String type, String title, String body, String collapseKey,
                Map<String, String> data) {
    }
}
