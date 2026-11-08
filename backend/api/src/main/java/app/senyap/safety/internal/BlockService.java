package app.senyap.safety.internal;

import app.senyap.identity.IdentityApi;
import app.senyap.identity.PublicIdentity;
import app.senyap.safety.ContentRef;
import app.senyap.safety.ContentType;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.outbox.OutboxWriter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Block (FS-07): dari konten (server menentukan akun di baliknya), daftar block (alias + tanggal saja), buka
 * block. Efek dua arah dibaca modul lain lewat {@link #blockedEitherWay}, yang tidak membedakan arah.
 */
@Service
class BlockService {

    record BlockView(@JsonProperty("id") String id, @JsonProperty("name") String name,
                     @JsonProperty("tag") String tag, @JsonProperty("color") String color,
                     @JsonProperty("created_at") Instant createdAt) {
    }

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final Tsid tsid;
    private final ContentDirectories contents;
    private final IdentityApi identity;

    BlockService(@Qualifier("safetyDatabase") ModuleDatabase db, @Qualifier("safetyOutbox") OutboxWriter outbox,
                 Tsid tsid, ContentDirectories contents, IdentityApi identity) {
        this.db = db;
        this.outbox = outbox;
        this.tsid = tsid;
        this.contents = contents;
        this.identity = identity;
    }

    Set<Long> blockedEitherWay(long userId, List<Long> others) {
        if (others.isEmpty()) {
            return Set.of();
        }
        var found = new HashSet<Long>();
        db.jdbc().sql("""
                        SELECT blocked_id AS other FROM blocks WHERE blocker_id = :u AND blocked_id IN (:o)
                        UNION
                        SELECT blocker_id AS other FROM blocks WHERE blocked_id = :u AND blocker_id IN (:o)""")
                .param("u", userId).param("o", List.copyOf(new HashSet<>(others)))
                .query(rs -> {
                    found.add(rs.getLong("other"));
                });
        return Set.copyOf(found);
    }

    void block(long blockerId, long blockedId, Long conversationId) {
        if (blockerId == blockedId) {
            return;
        }
        db.tx().executeWithoutResult(s -> {
            int inserted = db.jdbc().sql("""
                            INSERT INTO blocks (id, blocker_id, blocked_id, conversation_id) VALUES (:id, :a, :b, :c)
                            ON CONFLICT (blocker_id, blocked_id) DO NOTHING""")
                    .param("id", tsid.next()).param("a", blockerId).param("b", blockedId).param("c", conversationId)
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

    /** {@code POST /v1/blocks}: konten yang tidak terlihat oleh blocker → NOT_FOUND. */
    void blockByContent(long blockerId, ContentRef ref) {
        if (ref.type() == ContentType.MEDIA) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Jenis konten tidak dikenal.");
        }
        var snap = contents.snapshot(ref, blockerId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (snap.ownerUserId() == blockerId) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kamu tidak bisa memblokir dirimu sendiri.");
        }
        Long conversationId = ref.type() == ContentType.MESSAGE || ref.type() == ContentType.CONVERSATION
                ? ref.id() : null;
        block(blockerId, snap.ownerUserId(), conversationId);
    }

    List<BlockView> list(long blockerId, Long beforeId, int limitPlusOne) {
        record Row(long id, long blocked, Instant at) {
        }
        var rows = db.jdbc().sql("""
                        SELECT id, blocked_id, created_at FROM blocks WHERE blocker_id = :u AND id < :before
                        ORDER BY id DESC LIMIT :n""")
                .param("u", blockerId).param("before", beforeId == null ? Long.MAX_VALUE : beforeId)
                .param("n", limitPlusOne)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant())).list();
        Map<Long, PublicIdentity> ids = identity.resolveIdentities(rows.stream().map(Row::blocked).distinct().toList());
        return rows.stream().map(r -> {
            var p = ids.get(r.blocked());
            return new BlockView(Tsid.toString(r.id()), p == null ? "[dihapus]" : p.name(), p == null ? null : p.tag(),
                    p == null ? null : p.color(), r.at().truncatedTo(ChronoUnit.MINUTES));
        }).toList();
    }

    void unblock(long blockerId, long blockId) {
        int n = db.jdbc().sql("DELETE FROM blocks WHERE id = :id AND blocker_id = :u")
                .param("id", blockId).param("u", blockerId).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}
