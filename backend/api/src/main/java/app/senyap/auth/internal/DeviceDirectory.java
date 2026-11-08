package app.senyap.auth.internal;

import app.senyap.auth.DeviceApi;
import app.senyap.shared.db.ModuleDatabase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Implementasi {@link DeviceApi}: hanya device yang masih punya sesi (refresh token belum dicabut). */
@Service
class DeviceDirectory implements DeviceApi {

    private final ModuleDatabase db;

    DeviceDirectory(@Qualifier("authDatabase") ModuleDatabase db) {
        this.db = db;
    }

    @Override
    public Map<Long, List<PushTarget>> findPushTargets(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        var out = new LinkedHashMap<Long, List<PushTarget>>();
        db.jdbc().sql("""
                        SELECT d.id, d.user_id, d.platform, d.push_token FROM devices d
                        WHERE d.user_id IN (:ids) AND d.push_token IS NOT NULL
                          AND EXISTS (SELECT 1 FROM refresh_tokens r
                                      WHERE r.device_id = d.id AND r.revoked_at IS NULL AND r.expires_at > now())
                        ORDER BY d.id""")
                .param("ids", List.copyOf(userIds))
                .query(rs -> {
                    out.computeIfAbsent(rs.getLong("user_id"), k -> new ArrayList<>())
                            .add(new PushTarget(rs.getLong("id"), rs.getString("platform"), rs.getString("push_token")));
                });
        return out;
    }
}
