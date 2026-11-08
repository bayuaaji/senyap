package app.senyap.chat.internal;

import app.senyap.safety.ContentDirectory;
import app.senyap.safety.ContentRef;
import app.senyap.safety.ContentSnapshot;
import app.senyap.safety.ContentType;
import app.senyap.safety.ContentVisibility;
import app.senyap.shared.db.ModuleDatabase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Port konten safety untuk Bisik (FS-07): snapshot pesan + 20 pesan di sekitarnya, termasuk isi pesan yang sudah
 * ditarik (bukti tetap ada). Pelapor harus anggota percakapan. Pesan DM tidak bisa "disembunyikan" moderator;
 * tindakannya lewat sanksi akun.
 */
@Component
class ChatContentDirectory implements ContentDirectory {

    static final int CONTEXT = 20;

    private final ModuleDatabase db;

    ChatContentDirectory(@Qualifier("chatDatabase") ModuleDatabase db) {
        this.db = db;
    }

    @Override
    public Set<ContentType> supports() {
        return Set.of(ContentType.MESSAGE, ContentType.CONVERSATION);
    }

    @Override
    public Map<ContentRef, ContentSnapshot> snapshot(List<ContentRef> refs, Long viewerUserId) {
        var out = new HashMap<ContentRef, ContentSnapshot>();
        for (ContentRef ref : refs) {
            var conv = db.jdbc().sql("SELECT initiator_id, recipient_id, status FROM conversations WHERE id = :c")
                    .param("c", ref.id())
                    .query((rs, n) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3)})
                    .optional().orElse(null);
            if (conv == null) {
                continue;
            }
            long initiator = (long) conv[0];
            long recipient = (long) conv[1];
            if (viewerUserId != null && viewerUserId != initiator && viewerUserId != recipient) {
                continue;
            }
            List<ContentSnapshot.Message> context;
            long owner;
            String text = null;
            java.time.Instant at;
            if (ref.type() == ContentType.MESSAGE) {
                var msg = db.jdbc().sql("SELECT " + ChatRows.Message.COLUMNS
                                + " FROM messages WHERE conversation_id = :c AND seq = :s")
                        .param("c", ref.id()).param("s", ref.seq()).query((rs, n) -> ChatRows.Message.map(rs))
                        .optional().orElse(null);
                if (msg == null || "SYSTEM".equals(msg.type())) {
                    continue;
                }
                owner = msg.senderId();
                text = msg.body();
                at = msg.createdAt();
                context = messages("""
                        SELECT %s FROM messages WHERE conversation_id = :c AND seq BETWEEN :from AND :to ORDER BY seq"""
                        .formatted(ChatRows.Message.COLUMNS), ref.id(), Math.max(1, ref.seq() - CONTEXT / 2),
                        ref.seq() + CONTEXT / 2 - 1);
            } else {
                if (viewerUserId == null) {
                    continue; // akun di balik percakapan = lawan bicara viewer
                }
                owner = viewerUserId == initiator ? recipient : initiator;
                context = messages("""
                        SELECT * FROM (SELECT %s FROM messages WHERE conversation_id = :c AND seq >= :from AND seq <= :to
                                       ORDER BY seq DESC LIMIT %d) t ORDER BY seq"""
                        .formatted(ChatRows.Message.COLUMNS, CONTEXT), ref.id(), 1, Long.MAX_VALUE);
                at = context.isEmpty() ? java.time.Instant.now() : context.getLast().createdAt();
            }
            out.put(ref, new ContentSnapshot(ref, owner, 0, text, null, context, (String) conv[2], at));
        }
        return out;
    }

    private List<ContentSnapshot.Message> messages(String sql, long conversationId, long from, long to) {
        return db.jdbc().sql(sql).param("c", conversationId).param("from", from).param("to", to)
                .query((rs, n) -> {
                    var m = ChatRows.Message.map(rs);
                    String body = m.body() != null ? m.body() : m.mediaId() != null ? "[gambar]" : null;
                    return new ContentSnapshot.Message(m.seq(), m.senderId(), m.type(), body, m.retractedAt() != null,
                            m.createdAt());
                }).list();
    }

    @Override
    public boolean moderate(ContentRef ref, ContentVisibility visibility) {
        return false;
    }
}
