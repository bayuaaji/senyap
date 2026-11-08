package app.senyap.notifications.internal;

import java.util.Map;

/**
 * Pengirim push ke satu device (FCM untuk Android, APNs/FCM untuk iOS). Implementasi produksi (FCM HTTP v1
 * dengan service account dari KMS) belum ada; lokal/test memakai {@link LoggingPushSender}.
 */
interface PushSender {

    /** @return {@code false} bila token tidak berlaku lagi (device harus berhenti dikirimi). */
    boolean send(Message message);

    /** Satu push siap kirim. {@code token} rahasia; jangan ditulis ke log. */
    record Message(long deviceId, String platform, String token, String type, String title, String body,
                   String collapseKey, Map<String, String> data) {
    }
}
