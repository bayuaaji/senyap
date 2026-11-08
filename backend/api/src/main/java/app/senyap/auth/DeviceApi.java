package app.senyap.auth;

import java.util.List;
import java.util.Map;

/** API publik modul auth untuk device (aturan M3/M4: batch, DTO record). */
public interface DeviceApi {

    /**
     * Device yang punya push token aktif per user ID (device dengan refresh token yang belum dicabut).
     * User tanpa device seperti itu tidak ada di map.
     */
    Map<Long, List<PushTarget>> findPushTargets(List<Long> userIds);

    /** Tujuan push. {@code pushToken} rahasia: jangan ditulis ke log. */
    record PushTarget(long deviceId, String platform, String pushToken) {
    }
}
