package app.senyap.chat.internal;

import app.senyap.chat.OriginDirectory;
import app.senyap.chat.internal.ChatRows.Conversation;
import app.senyap.chat.internal.ChatViews.Badge;
import app.senyap.chat.internal.ChatViews.ConversationView;
import app.senyap.chat.internal.ChatViews.MessageView;
import app.senyap.chat.internal.ChatViews.Origin;
import app.senyap.chat.internal.ChatViews.Person;
import app.senyap.chat.internal.ChatViews.SyncItem;
import app.senyap.identity.IdentityApi;
import app.senyap.media.DmImage;
import app.senyap.media.DmMediaApi;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.web.Cursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Baca Bisik: inbox, permintaan, detail, riwayat, gap fill, sinkron. */
@Service
class ChatQueries {

    enum Box { INBOX, REQUESTS }

    private record Row(Conversation c, long myDelivered, long myRead, long peerDelivered, long peerRead,
                       long unread, ChatRows.Message last) {
    }

    private final ModuleDatabase db;
    private final IdentityApi identity;
    private final OriginDirectory origins;
    private final DmMediaApi media;
    private final ChatService chat;

    ChatQueries(@Qualifier("chatDatabase") ModuleDatabase db, IdentityApi identity, OriginDirectory origins,
                DmMediaApi media, ChatService chat) {
        this.db = db;
        this.identity = identity;
        this.origins = origins;
        this.media = media;
        this.chat = chat;
    }

    private static final String SELECT = "SELECT " + Conversation.COLUMNS + """
            , m.last_delivered_seq AS my_delivered, m.last_read_seq AS my_read,
              p.last_delivered_seq AS peer_delivered, p.last_read_seq AS peer_read,
              (SELECT count(*) FROM messages x WHERE x.conversation_id = c.id AND x.seq > m.last_read_seq
                 AND x.sender_id <> m.user_id AND x.type <> 'SYSTEM') AS unread,
              lm.conversation_id AS lm_conversation_id, lm.seq AS lm_seq, lm.sender_id AS lm_sender_id,
              lm.client_msg_id AS lm_client_msg_id, lm.type AS lm_type, lm.body AS lm_body, lm.media_id AS lm_media_id,
              lm.deleted_for_all_at AS lm_deleted_for_all_at, lm.created_at AS lm_created_at
            FROM conversation_members m
            JOIN conversations c ON c.id = m.conversation_id
            JOIN conversation_members p ON p.conversation_id = c.id AND p.user_id <> m.user_id
            LEFT JOIN messages lm ON lm.conversation_id = c.id AND lm.seq = c.last_seq
            WHERE m.user_id = :me AND m.left_at IS NULL
            """;

    List<ConversationView> list(long me, Box box, String cursor, int limit) {
        var sql = new StringBuilder(SELECT).append(" AND m.hidden_at IS NULL ");
        if (box == Box.REQUESTS) {
            sql.append(" AND c.status = 'REQUESTED' AND c.recipient_id = :me ");
        } else {
            sql.append(" AND NOT (c.status IN ('REQUESTED', 'DECLINED') AND c.recipient_id = :me) ");
        }
        Instant afterAt = null;
        long afterId = 0;
        if (cursor != null) {
            var cur = Cursor.decode(cursor);
            afterAt = Instant.EPOCH.plus(cur.getLong("at"), java.time.temporal.ChronoUnit.MICROS);
            afterId = cur.getLong("id");
            sql.append(" AND (c.last_message_at, c.id) < (:at, :aid) ");
        }
        sql.append(" ORDER BY c.last_message_at DESC, c.id DESC LIMIT :n");
        var q = db.jdbc().sql(sql.toString()).param("me", me).param("n", limit);
        if (afterAt != null) {
            q = q.param("at", ChatService.ts(afterAt)).param("aid", afterId);
        }
        return render(me, q.query((rs, i) -> map(rs)).list());
    }

    static Cursor cursorOf(ConversationView v) {
        // last_message_at di view dibulatkan ke menit, jadi cursor memakai nilai asli dari DB lewat id.
        return Cursor.of(Map.of("at", Long.toString(v.lastMessageAtExact()), "id", v.id()));
    }

    ConversationView detail(long me, long conversationId) {
        chat.memberConversation(me, conversationId);
        var rows = db.jdbc().sql(SELECT + " AND c.id = :id").param("me", me).param("id", conversationId)
                .query((rs, i) -> map(rs)).list();
        return render(me, rows).getFirst();
    }

    /** Riwayat: {@code beforeSeq} (mundur) atau {@code afterSeq} (gap fill, maju). Urut seq naik. */
    List<MessageView> messages(long me, long conversationId, Long beforeSeq, Long afterSeq, int limit) {
        chat.memberConversation(me, conversationId);
        List<ChatRows.Message> rows;
        if (afterSeq != null) {
            rows = db.jdbc().sql("SELECT " + ChatRows.Message.COLUMNS + """
                             FROM messages WHERE conversation_id = :c AND seq > :s ORDER BY seq LIMIT :n""")
                    .param("c", conversationId).param("s", afterSeq).param("n", limit)
                    .query((rs, i) -> ChatRows.Message.map(rs)).list();
        } else {
            rows = new ArrayList<>(db.jdbc().sql("SELECT " + ChatRows.Message.COLUMNS + """
                             FROM messages WHERE conversation_id = :c AND seq < :s ORDER BY seq DESC LIMIT :n""")
                    .param("c", conversationId).param("s", beforeSeq == null ? Long.MAX_VALUE : beforeSeq)
                    .param("n", limit)
                    .query((rs, i) -> ChatRows.Message.map(rs)).list());
            java.util.Collections.reverse(rows);
        }
        var images = images(rows);
        return rows.stream().map(m -> MessageView.of(m, me, m.mediaId() == null ? null : images.get(m.mediaId())))
                .toList();
    }

    /** Sinkron saat reconnect: percakapan yang {@code last_seq}-nya lebih baru dari yang diketahui client. */
    List<SyncItem> sync(long me, Map<Long, Long> known, int limit) {
        var rows = db.jdbc().sql("""
                        SELECT c.id, c.last_seq, c.status FROM conversation_members m
                        JOIN conversations c ON c.id = m.conversation_id
                        WHERE m.user_id = :me AND m.left_at IS NULL
                          AND NOT (c.status = 'DECLINED' AND c.recipient_id = :me)
                        ORDER BY c.last_message_at DESC LIMIT :n""")
                .param("me", me).param("n", limit)
                .query((rs, i) -> new SyncItem(Tsid.toString(rs.getLong(1)), rs.getLong(2), rs.getString(3)))
                .list();
        var out = new ArrayList<SyncItem>();
        for (var r : rows) {
            Long k = known.get(Tsid.parse(r.conversationId()));
            if (k == null || k < r.lastSeq()) {
                out.add(r);
            }
        }
        return out;
    }

    Badge badge(long me) {
        return db.jdbc().sql("""
                        SELECT
                          COALESCE(SUM((SELECT count(*) FROM messages x WHERE x.conversation_id = c.id
                                AND x.seq > m.last_read_seq AND x.sender_id <> m.user_id AND x.type <> 'SYSTEM'))
                            FILTER (WHERE c.status IN ('ACTIVE', 'CLOSED')), 0) AS unread,
                          count(*) FILTER (WHERE c.status = 'REQUESTED' AND c.recipient_id = :me) AS requests
                        FROM conversation_members m JOIN conversations c ON c.id = m.conversation_id
                        WHERE m.user_id = :me AND m.left_at IS NULL AND m.hidden_at IS NULL""")
                .param("me", me)
                .query((rs, i) -> new Badge(rs.getLong("unread"), rs.getLong("requests"))).single();
    }

    // ---------------------------------------------------------------- render

    private static Row map(ResultSet rs) throws SQLException {
        var c = Conversation.map(rs);
        ChatRows.Message last = null;
        long lmConv = rs.getLong("lm_conversation_id");
        if (!rs.wasNull()) {
            long media = rs.getLong("lm_media_id");
            boolean noMedia = rs.wasNull();
            last = new ChatRows.Message(lmConv, rs.getLong("lm_seq"), rs.getLong("lm_sender_id"),
                    rs.getObject("lm_client_msg_id", java.util.UUID.class), rs.getString("lm_type"),
                    rs.getString("lm_body"), noMedia ? null : media, ChatRows.instant(rs, "lm_deleted_for_all_at"),
                    ChatRows.instant(rs, "lm_created_at"));
        }
        return new Row(c, rs.getLong("my_delivered"), rs.getLong("my_read"), rs.getLong("peer_delivered"),
                rs.getLong("peer_read"), rs.getLong("unread"), last);
    }

    private List<ConversationView> render(long me, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var peers = identity.resolveIdentities(rows.stream().map(r -> r.c().peerOf(me)).distinct().toList());
        var originKeys = rows.stream().map(r -> new OriginDirectory.OriginKey(r.c().originType(), r.c().originId()))
                .distinct().toList();
        var originInfo = origins.findOrigins(originKeys);
        var lastMessages = rows.stream().map(Row::last).filter(Objects::nonNull).toList();
        var images = images(lastMessages);
        var out = new ArrayList<ConversationView>(rows.size());
        for (Row r : rows) {
            var c = r.c();
            var key = new OriginDirectory.OriginKey(c.originType(), c.originId());
            var info = originInfo.get(key);
            String excerpt = info == null ? null : info.excerpt();
            MessageView last = r.last() == null ? null
                    : preview(MessageView.of(r.last(), me, r.last().mediaId() == null ? null : images.get(r.last().mediaId())));
            out.add(new ConversationView(Tsid.toString(c.id()), c.status().name(),
                    c.initiatorId() == me ? "initiator" : "recipient", Person.of(peers.get(c.peerOf(me))),
                    new Origin(c.originType(), Tsid.toString(c.originId()), excerpt), c.lastSeq(), last, r.unread(),
                    r.myDelivered(), r.myRead(), r.peerDelivered(), r.peerRead(),
                    ChatViews.minute(c.lastMessageAt()), ChatViews.minute(c.createdAt()),
                    c.lastMessageAt() == null ? 0 : java.time.temporal.ChronoUnit.MICROS.between(Instant.EPOCH, c.lastMessageAt())));
        }
        return out;
    }

    /** Cuplikan inbox: maksimal 100 karakter. */
    private static MessageView preview(MessageView m) {
        if (m.body() == null || m.body().length() <= 100) {
            return m;
        }
        return new MessageView(m.conversationId(), m.seq(), m.sender(), m.type(), m.body().substring(0, 100) + "…",
                m.media(), m.retracted(), m.clientMsgId(), m.createdAt());
    }

    private Map<Long, DmImage> images(List<ChatRows.Message> messages) {
        var ids = messages.stream().map(ChatRows.Message::mediaId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? new HashMap<>() : media.findDmImages(ids);
    }
}
