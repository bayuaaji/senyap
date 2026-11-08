package app.senyap.safety.internal;

import app.senyap.safety.SafetyApi;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.outbox.OutboxWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Implementasi minimal {@link SafetyApi} di atas tabel {@code safety.blocks} (Fase 2). */
@Service
class BlockService implements SafetyApi {

    private final ModuleDatabase db;
    private final OutboxWriter outbox;

    BlockService(@Qualifier("safetyDatabase") ModuleDatabase db, @Qualifier("safetyOutbox") OutboxWriter outbox) {
        this.db = db;
        this.outbox = outbox;
    }

    @Override
    public Set<Long> blockedEitherWay(long userId, List<Long> others) {
        if (others.isEmpty()) {
            return Set.of();
        }
        var found = new HashSet<Long>();
        db.jdbc().sql("""
                        SELECT blocked_id AS other FROM blocks WHERE blocker_id = :u AND blocked_id IN (:o)
                        UNION
                        SELECT blocker_id AS other FROM blocks WHERE blocked_id = :u AND blocker_id IN (:o)""")
                .param("u", userId).param("o", List.copyOf(others))
                .query(rs -> {
                    found.add(rs.getLong("other"));
                });
        return Set.copyOf(found);
    }

    @Override
    public void block(long blockerId, long blockedId, Long conversationId) {
        if (blockerId == blockedId) {
            return;
        }
        db.tx().executeWithoutResult(s -> {
            int inserted = db.jdbc().sql("""
                            INSERT INTO blocks (blocker_id, blocked_id, conversation_id) VALUES (:a, :b, :c)
                            ON CONFLICT (blocker_id, blocked_id) DO NOTHING""")
                    .param("a", blockerId).param("b", blockedId).param("c", conversationId)
                    .update();
            if (inserted == 1) {
                var payload = new HashMap<String, Object>();
                payload.put("blocker_id", blockerId);
                payload.put("blocked_id", blockedId);
                if (conversationId != null) {
                    payload.put("conversation_id", conversationId);
                }
                outbox.append("user.blocked", Long.toString(blockerId), payload);
            }
        });
    }
}
