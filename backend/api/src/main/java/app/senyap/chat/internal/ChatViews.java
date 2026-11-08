package app.senyap.chat.internal;

import app.senyap.identity.PublicIdentity;
import app.senyap.media.DmImage;
import app.senyap.shared.id.Tsid;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bentuk respons REST + isi frame WS untuk Bisik. Tidak ada user ID: lawan bicara hanya {@code public_id} + alias,
 * pengirim pesan hanya {@code "me"}/{@code "them"}. Semua waktu dibulatkan ke menit (functional spec §4).
 */
final class ChatViews {

    private ChatViews() {
    }

    static Instant minute(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MINUTES);
    }

    record Person(@JsonProperty("public_id") UUID publicId, @JsonProperty("name") String name,
                  @JsonProperty("tag") String tag, @JsonProperty("color") String color) {

        static final Person DELETED = new Person(null, "[dihapus]", null, "#9E9E9E");

        static Person of(PublicIdentity p) {
            return p == null ? DELETED : new Person(p.publicId(), p.name(), p.tag(), p.color());
        }
    }

    record Origin(@JsonProperty("type") String type, @JsonProperty("id") String id,
                  @JsonProperty("excerpt") String excerpt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Media(@JsonProperty("id") String id, @JsonProperty("url") String url,
                 @JsonProperty("mime_type") String mimeType, @JsonProperty("expires_at") Instant expiresAt) {

        static Media of(DmImage img) {
            return img == null ? null : new Media(Tsid.toString(img.mediaId()), img.url(), img.mimeType(),
                    img.urlExpiresAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MessageView(@JsonProperty("conversation_id") String conversationId, @JsonProperty("seq") long seq,
                       @JsonProperty("sender") String sender, @JsonProperty("type") String type,
                       @JsonProperty("body") String body, @JsonProperty("media") Media media,
                       @JsonProperty("retracted") boolean retracted,
                       @JsonProperty("client_msg_id") UUID clientMsgId,
                       @JsonProperty("created_at") Instant createdAt) {

        static MessageView of(ChatRows.Message m, long viewerUserId, DmImage image) {
            boolean mine = m.senderId() == viewerUserId;
            boolean retracted = m.retractedAt() != null;
            return new MessageView(Tsid.toString(m.conversationId()), m.seq(), mine ? "me" : "them", m.type(),
                    retracted ? null : m.body(), retracted ? null : Media.of(image), retracted,
                    mine ? m.clientMsgId() : null, minute(m.createdAt()));
        }

        /** Isi frame {@code msg.new}. */
        Map<String, Object> asFrameData() {
            var d = new LinkedHashMap<String, Object>();
            d.put("conversation_id", conversationId);
            d.put("seq", seq);
            d.put("sender", sender);
            d.put("type", type);
            if (body != null) {
                d.put("body", body);
            }
            if (media != null) {
                var md = new LinkedHashMap<String, Object>();
                md.put("id", media.id());
                md.put("url", media.url());
                md.put("mime_type", media.mimeType());
                md.put("expires_at", media.expiresAt().toString());
                d.put("media", md);
            }
            if (retracted) {
                d.put("retracted", true);
            }
            if (clientMsgId != null) {
                d.put("client_msg_id", clientMsgId.toString());
            }
            d.put("created_at", createdAt.toString());
            return d;
        }
    }

    /** {@code msg.ack} / respons kirim REST. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Ack(@JsonProperty("client_msg_id") UUID clientMsgId,
               @JsonProperty("conversation_id") String conversationId, @JsonProperty("seq") long seq,
               @JsonProperty("created_at") Instant createdAt,
               @JsonProperty("support_card") Boolean supportCard) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ConversationView(@JsonProperty("id") String id, @JsonProperty("status") String status,
                            @JsonProperty("role") String role, @JsonProperty("peer") Person peer,
                            @JsonProperty("origin") Origin origin, @JsonProperty("last_seq") long lastSeq,
                            @JsonProperty("last_message") MessageView lastMessage,
                            @JsonProperty("unread_count") long unreadCount,
                            @JsonProperty("my_delivered_up_to") long myDeliveredUpTo,
                            @JsonProperty("my_read_up_to") long myReadUpTo,
                            @JsonProperty("peer_delivered_up_to") long peerDeliveredUpTo,
                            @JsonProperty("peer_read_up_to") long peerReadUpTo,
                            @JsonProperty("last_message_at") Instant lastMessageAt,
                            @JsonProperty("created_at") Instant createdAt,
                            @com.fasterxml.jackson.annotation.JsonIgnore long lastMessageAtExact) {
    }

    record SyncItem(@JsonProperty("conversation_id") String conversationId, @JsonProperty("last_seq") long lastSeq,
                    @JsonProperty("status") String status) {
    }

    record Badge(@JsonProperty("unread_messages") long unreadMessages, @JsonProperty("requests") long requests) {
    }
}
