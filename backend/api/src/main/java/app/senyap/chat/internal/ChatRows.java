package app.senyap.chat.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Baris tabel schema chat (internal). */
final class ChatRows {

    private ChatRows() {
    }

    enum Status { REQUESTED, ACTIVE, CLOSED, DECLINED }

    record Conversation(long id, String originType, long originId, long initiatorId, long recipientId, Status status,
                        long lastSeq, Instant lastMessageAt, Instant createdAt) {

        boolean isMember(long userId) {
            return userId == initiatorId || userId == recipientId;
        }

        long peerOf(long userId) {
            return userId == initiatorId ? recipientId : initiatorId;
        }

        static final String COLUMNS =
                "c.id, c.origin_type, c.origin_id, c.initiator_id, c.recipient_id, c.status, c.last_seq, "
                        + "c.last_message_at, c.created_at ";

        static Conversation map(ResultSet rs) throws SQLException {
            return new Conversation(rs.getLong("id"), rs.getString("origin_type"), rs.getLong("origin_id"),
                    rs.getLong("initiator_id"), rs.getLong("recipient_id"), Status.valueOf(rs.getString("status")),
                    rs.getLong("last_seq"), instant(rs, "last_message_at"), instant(rs, "created_at"));
        }
    }

    record Member(long userId, long lastDeliveredSeq, long lastReadSeq, Instant leftAt, Instant hiddenAt) {

        static Member map(ResultSet rs) throws SQLException {
            return new Member(rs.getLong("user_id"), rs.getLong("last_delivered_seq"), rs.getLong("last_read_seq"),
                    instant(rs, "left_at"), instant(rs, "hidden_at"));
        }
    }

    record Message(long conversationId, long seq, long senderId, UUID clientMsgId, String type, String body,
                   Long mediaId, Instant retractedAt, Instant createdAt) {

        static final String COLUMNS =
                "conversation_id, seq, sender_id, client_msg_id, type, body, media_id, deleted_for_all_at, created_at ";

        static Message map(ResultSet rs) throws SQLException {
            long media = rs.getLong("media_id");
            Long mediaId = rs.wasNull() ? null : media;
            return new Message(rs.getLong("conversation_id"), rs.getLong("seq"), rs.getLong("sender_id"),
                    rs.getObject("client_msg_id", UUID.class), rs.getString("type"), rs.getString("body"),
                    mediaId, instant(rs, "deleted_for_all_at"), instant(rs, "created_at"));
        }
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        var t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
