package app.senyap.chat.internal;

import app.senyap.chat.OriginDirectory;
import app.senyap.chat.internal.ChatRows.Conversation;
import app.senyap.chat.internal.ChatRows.Status;
import app.senyap.chat.internal.ChatViews.Ack;
import app.senyap.contracts.ws.FrameType;
import app.senyap.contracts.ws.WsFrame;
import app.senyap.identity.AccountStanding;
import app.senyap.identity.AccountStatus;
import app.senyap.identity.IdentityApi;
import app.senyap.media.DmMediaApi;
import app.senyap.safety.SafetyApi;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.outbox.OutboxWriter;
import app.senyap.shared.ratelimit.RateLimiter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Perintah Bisik (functional spec FS-05). Setiap tulis = satu transaksi {@code chat} + outbox (M6/M7).
 * Panggilan ke modul lain (identity, safety, posts, media) dilakukan di luar transaksi.
 */
@Service
class ChatService {

    static final int OPENING_MAX = 500;
    static final int TEXT_MAX = 2000;
    static final Duration RETRACT_WINDOW = Duration.ofMinutes(10);
    static final Duration REQUEST_TTL = Duration.ofDays(7);
    /** Isi pesan SYSTEM saat lawan bicara keluar / memblokir (tidak dibedakan). */
    static final String SYSTEM_LEFT = "left";

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final Tsid tsid;
    private final IdentityApi identity;
    private final SafetyApi safety;
    private final OriginDirectory origins;
    private final DmMediaApi media;
    private final RateLimiter rateLimiter;
    private final RealtimePublisher realtime;
    private final ObjectProvider<ChatOutboxPump> pump;
    private final Cache<Long, AccountStanding> standings = Caffeine.newBuilder()
            .maximumSize(100_000).expireAfterWrite(Duration.ofSeconds(60)).build();

    ChatService(@Qualifier("chatDatabase") ModuleDatabase db, @Qualifier("chatOutbox") OutboxWriter outbox, Tsid tsid,
                IdentityApi identity, SafetyApi safety, OriginDirectory origins, DmMediaApi media,
                RateLimiter rateLimiter, RealtimePublisher realtime, ObjectProvider<ChatOutboxPump> pump) {
        this.db = db;
        this.outbox = outbox;
        this.tsid = tsid;
        this.identity = identity;
        this.safety = safety;
        this.origins = origins;
        this.media = media;
        this.rateLimiter = rateLimiter;
        this.realtime = realtime;
        this.pump = pump;
    }

    // ---------------------------------------------------------------- request

    /** Mulai Bisik dari post/balasan. Idempotent untuk {@code clientMsgId} yang sama. */
    long start(long me, String originType, long originId, String firstMessage, UUID clientMsgId) {
        String text = firstMessage == null ? "" : firstMessage.strip();
        if (text.isEmpty() || text.length() > OPENING_MAX) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Pesan pembuka 1–500 karakter.");
        }
        var existing = db.jdbc().sql("""
                        SELECT c.id, d.client_msg_id FROM conversations c
                        LEFT JOIN message_dedup d ON d.conversation_id = c.id AND d.sender_id = :me AND d.seq = 1
                        WHERE c.origin_type = :t AND c.origin_id = :o AND c.initiator_id = :me""")
                .param("t", originType).param("o", originId).param("me", me)
                .query((rs, n) -> Map.entry(rs.getLong(1), Optional.ofNullable(rs.getObject(2, UUID.class))))
                .optional();
        if (existing.isPresent()) {
            if (existing.get().getValue().filter(clientMsgId::equals).isPresent()) {
                return existing.get().getKey(); // kirim ulang request yang sama
            }
            throw new ApiException(ErrorCode.ALREADY_WHISPERED);
        }
        var key = new OriginDirectory.OriginKey(originType, originId);
        var origin = origins.findOrigins(List.of(key)).get(key);
        if (origin == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        long recipient = origin.ownerUserId();
        if (recipient == me) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kamu tidak bisa berbisik ke dirimu sendiri.");
        }
        var recipientStanding = identity.findStandings(List.of(recipient)).get(recipient);
        if (recipientStanding == null || !recipientStanding.status().canUseSession()
                || !safety.blockedEitherWay(me, List.of(recipient)).isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND); // block tidak dibocorkan
        }
        int quota = switch (standing(me).tier(Instant.now())) {
            case NEW -> 3;
            case NORMAL -> 20;
            case LOW_TRUST -> 0;
        };
        try {
            rateLimiter.hit("whisper:" + me, quota, Duration.ofDays(1));
        } catch (ApiException e) {
            throw ApiException.quotaExceeded("WHISPER", "Kamu sudah mencapai batas bisikan hari ini.", e.retryAfter());
        }
        long id = tsid.next();
        var now = now();
        try {
            db.tx().executeWithoutResult(s -> {
                db.jdbc().sql("""
                                INSERT INTO conversations (id, origin_type, origin_id, initiator_id, recipient_id, status,
                                                           last_seq, last_message_at, created_at)
                                VALUES (:id, :t, :o, :me, :r, 'REQUESTED', 1, :now, :now)""")
                        .param("id", id).param("t", originType).param("o", originId).param("me", me)
                        .param("r", recipient).param("now", ts(now)).update();
                db.jdbc().sql("""
                                INSERT INTO conversation_members (conversation_id, user_id, last_delivered_seq, last_read_seq)
                                VALUES (:id, :me, 1, 1), (:id, :r, 0, 0)""")
                        .param("id", id).param("me", me).param("r", recipient).update();
                insertMessage(id, 1, me, clientMsgId, "TEXT", text, null, now);
                outbox.append("conversation.requested", Long.toString(id), Map.of(
                        "conversation_id", id, "initiator_id", me, "recipient_id", recipient));
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.ALREADY_WHISPERED);
        }
        kick();
        return id;
    }

    void accept(long me, long conversationId) {
        var c = memberConversation(me, conversationId);
        if (c.recipientId() != me) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (c.status() == Status.ACTIVE) {
            return;
        }
        if (c.status() != Status.REQUESTED) {
            throw new ApiException(ErrorCode.CONVERSATION_CLOSED);
        }
        boolean changed = Boolean.TRUE.equals(db.tx().execute(s -> {
            int n = db.jdbc().sql("""
                            UPDATE conversations SET status = 'ACTIVE', accepted_at = now()
                            WHERE id = :id AND status = 'REQUESTED'""")
                    .param("id", conversationId).update();
            if (n == 0) {
                return false;
            }
            db.jdbc().sql("""
                            UPDATE conversation_members SET last_delivered_seq = GREATEST(last_delivered_seq, 1),
                                   last_read_seq = GREATEST(last_read_seq, 1)
                            WHERE conversation_id = :id AND user_id = :me""")
                    .param("id", conversationId).param("me", me).update();
            outbox.append("conversation.accepted", Long.toString(conversationId), Map.of(
                    "conversation_id", conversationId, "initiator_id", c.initiatorId(), "recipient_id", me));
            return true;
        }));
        if (changed) {
            kick();
        }
    }

    void decline(long me, long conversationId) {
        var c = memberConversation(me, conversationId);
        if (c.recipientId() != me) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (c.status() == Status.DECLINED) {
            return;
        }
        if (c.status() != Status.REQUESTED) {
            throw new ApiException(ErrorCode.CONVERSATION_CLOSED);
        }
        db.tx().executeWithoutResult(s -> declineTx(c, "DECLINED"));
        kick();
    }

    private void declineTx(Conversation c, String reason) {
        int n = db.jdbc().sql("""
                        UPDATE conversations SET status = 'DECLINED', closed_at = now(), closed_reason = :r
                        WHERE id = :id AND status = 'REQUESTED'""")
                .param("r", reason).param("id", c.id()).update();
        if (n == 1) {
            outbox.append("conversation.declined", Long.toString(c.id()), Map.of(
                    "conversation_id", c.id(), "initiator_id", c.initiatorId(), "recipient_id", c.recipientId(),
                    "reason", reason));
        }
    }

    /** Request REQUESTED yang lebih tua dari 7 hari → DECLINED (job worker). @return jumlah */
    int expireRequests(int batch) {
        List<Conversation> old = db.jdbc().sql("SELECT " + Conversation.COLUMNS + """
                         FROM conversations c WHERE c.status = 'REQUESTED' AND c.created_at < :cut
                        ORDER BY c.created_at LIMIT :n""")
                .param("cut", ts(Instant.now().minus(REQUEST_TTL))).param("n", batch)
                .query((rs, i) -> Conversation.map(rs)).list();
        for (Conversation c : old) {
            db.tx().executeWithoutResult(s -> declineTx(c, "EXPIRED"));
        }
        if (!old.isEmpty()) {
            kick();
        }
        return old.size();
    }

    // ---------------------------------------------------------------- keluar / block / sembunyikan

    void leave(long me, long conversationId) {
        var c = memberConversation(me, conversationId);
        if (c.status() == Status.REQUESTED && c.recipientId() == me) {
            decline(me, conversationId);
            hideTx(me, conversationId, true);
            return;
        }
        db.tx().executeWithoutResult(s -> closeTx(c, me, "LEFT"));
        kick();
    }

    void block(long me, long conversationId) {
        var c = memberConversation(me, conversationId);
        safety.block(me, c.peerOf(me), conversationId);
        db.tx().executeWithoutResult(s -> closeTx(c, me, "BLOCKED"));
        kick();
    }

    /** Consumer {@code user.blocked}: tutup semua percakapan aktif/request antara keduanya. */
    void closeBetween(long blocker, long blocked) {
        List<Conversation> open = db.jdbc().sql("SELECT " + Conversation.COLUMNS + """
                         FROM conversations c WHERE c.status IN ('REQUESTED', 'ACTIVE')
                          AND ((c.initiator_id = :a AND c.recipient_id = :b) OR (c.initiator_id = :b AND c.recipient_id = :a))""")
                .param("a", blocker).param("b", blocked)
                .query((rs, i) -> Conversation.map(rs)).list();
        for (Conversation c : open) {
            db.tx().executeWithoutResult(s -> closeTx(c, blocker, "BLOCKED"));
        }
        if (!open.isEmpty()) {
            kick();
        }
    }

    /**
     * Menutup percakapan oleh {@code by}: {@code left_at} diisi, status {@code CLOSED}, pesan sistem "left" untuk
     * lawan bicara (bila sudah ACTIVE). Lawan bicara tidak bisa membedakan keluar dan block. Idempotent.
     */
    private void closeTx(Conversation c, long by, String reason) {
        var locked = db.jdbc().sql("SELECT " + Conversation.COLUMNS + " FROM conversations c WHERE c.id = :id FOR UPDATE")
                .param("id", c.id()).query((rs, i) -> Conversation.map(rs)).single();
        db.jdbc().sql("""
                        UPDATE conversation_members SET left_at = COALESCE(left_at, now())
                        WHERE conversation_id = :id AND user_id = :u""")
                .param("id", c.id()).param("u", by).update();
        if (locked.status() != Status.ACTIVE && locked.status() != Status.REQUESTED) {
            return;
        }
        if (locked.status() == Status.ACTIVE) {
            long seq = nextSeq(c.id(), false);
            insertMessage(c.id(), seq, by, UUID.randomUUID(), "SYSTEM", SYSTEM_LEFT, null, now());
            outbox.append("message.created", Long.toString(c.id()), Map.of("conversation_id", c.id(), "seq", seq));
        }
        db.jdbc().sql("""
                        UPDATE conversations SET status = 'CLOSED', closed_at = now(), closed_reason = :r
                        WHERE id = :id""")
                .param("r", reason).param("id", c.id()).update();
        outbox.append("conversation.closed", Long.toString(c.id()), Map.of(
                "conversation_id", c.id(), "initiator_id", c.initiatorId(), "recipient_id", c.recipientId(),
                "closed_by", by));
    }

    /** Hapus dari inbox sendiri; muncul lagi bila ada pesan baru. */
    void hide(long me, long conversationId) {
        memberConversation(me, conversationId);
        hideTx(me, conversationId, false);
    }

    private void hideTx(long me, long conversationId, boolean ignoreMissing) {
        db.jdbc().sql("UPDATE conversation_members SET hidden_at = now() WHERE conversation_id = :id AND user_id = :me")
                .param("id", conversationId).param("me", me).update();
    }

    // ---------------------------------------------------------------- pesan

    /** Hasil kirim. {@code duplicate} = {@code client_msg_id} ini sudah pernah tersimpan (ack lama). */
    record Sent(Ack ack, boolean duplicate) {
    }

    Sent send(long me, long conversationId, UUID clientMsgId, String type, String body, Long mediaId) {
        if (clientMsgId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "client_msg_id wajib.");
        }
        var prior = dedup(conversationId, me, clientMsgId);
        if (prior.isPresent()) {
            return new Sent(prior.get(), true);
        }
        var c = memberConversation(me, conversationId);
        switch (c.status()) {
            case ACTIVE -> { }
            case REQUESTED -> throw new ApiException(ErrorCode.CONVERSATION_PENDING);
            default -> throw new ApiException(ErrorCode.CONVERSATION_CLOSED);
        }
        String text = body == null ? null : body.strip();
        if ("TEXT".equals(type)) {
            if (text == null || text.isEmpty() || text.length() > TEXT_MAX) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Pesan 1–2.000 karakter.");
            }
            mediaId = null;
        } else if ("IMAGE".equals(type)) {
            if (mediaId == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "media_id wajib untuk gambar.");
            }
            var img = media.findDmImages(List.of(mediaId)).get(mediaId);
            if (img == null || img.ownerUserId() != me) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gambar belum siap.");
            }
            if (text != null && (text.isEmpty() || text.length() > TEXT_MAX)) {
                text = text.isEmpty() ? null : text.substring(0, TEXT_MAX);
            }
        } else {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tipe pesan tidak dikenal.");
        }
        if (!safety.blockedEitherWay(me, List.of(c.peerOf(me))).isEmpty()) {
            throw new ApiException(ErrorCode.CONVERSATION_CLOSED);
        }
        int perMinute = switch (standing(me).tier(Instant.now())) {
            case NEW -> 30;
            case NORMAL -> 60;
            case LOW_TRUST -> 10;
        };
        rateLimiter.hit("dm:" + me, perMinute, Duration.ofMinutes(1));

        final String finalText = text;
        final Long finalMedia = mediaId;
        var now = now();
        Sent sent = db.tx().execute(s -> {
            int inserted = db.jdbc().sql("""
                            INSERT INTO message_dedup (conversation_id, sender_id, client_msg_id, seq, created_at)
                            VALUES (:c, :me, :m, 0, :now) ON CONFLICT DO NOTHING""")
                    .param("c", conversationId).param("me", me).param("m", clientMsgId).param("now", ts(now))
                    .update();
            if (inserted == 0) {
                return null; // kirim ulang bersamaan: ack lama dibaca setelah transaksi
            }
            long seq = nextSeq(conversationId, true);
            insertMessage(conversationId, seq, me, clientMsgId, type, finalText, finalMedia, now, false);
            db.jdbc().sql("""
                            UPDATE message_dedup SET seq = :seq
                            WHERE conversation_id = :c AND sender_id = :me AND client_msg_id = :m""")
                    .param("seq", seq).param("c", conversationId).param("me", me).param("m", clientMsgId).update();
            db.jdbc().sql("""
                            UPDATE conversation_members SET hidden_at = NULL,
                                   last_read_seq = CASE WHEN user_id = :me THEN :seq ELSE last_read_seq END,
                                   last_delivered_seq = CASE WHEN user_id = :me THEN :seq ELSE last_delivered_seq END
                            WHERE conversation_id = :c""")
                    .param("me", me).param("seq", seq).param("c", conversationId).update();
            outbox.append("message.created", Long.toString(conversationId),
                    Map.of("conversation_id", conversationId, "seq", seq));
            return new Sent(new Ack(clientMsgId, Tsid.toString(conversationId), seq, ChatViews.minute(now)), false);
        });
        if (sent == null) {
            return new Sent(dedup(conversationId, me, clientMsgId)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL)), true);
        }
        kick();
        return sent;
    }

    private Optional<Ack> dedup(long conversationId, long me, UUID clientMsgId) {
        return db.jdbc().sql("""
                        SELECT seq, created_at FROM message_dedup
                        WHERE conversation_id = :c AND sender_id = :me AND client_msg_id = :m AND seq > 0""")
                .param("c", conversationId).param("me", me).param("m", clientMsgId)
                .query((rs, n) -> new Ack(clientMsgId, Tsid.toString(conversationId), rs.getLong(1),
                        ChatViews.minute(ChatRows.instant(rs, "created_at"))))
                .optional();
    }

    /** {@code UPDATE … last_seq = last_seq + 1 RETURNING} (functional spec FS-05 §5.5, jaminan urutan). */
    private long nextSeq(long conversationId, boolean requireActive) {
        return db.jdbc().sql("UPDATE conversations SET last_seq = last_seq + 1, last_message_at = now() WHERE id = :id"
                        + (requireActive ? " AND status = 'ACTIVE'" : "") + " RETURNING last_seq")
                .param("id", conversationId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_CLOSED));
    }

    private void insertMessage(long conversationId, long seq, long sender, UUID clientMsgId, String type, String body,
                               Long mediaId, Instant at) {
        insertMessage(conversationId, seq, sender, clientMsgId, type, body, mediaId, at, true);
    }

    private void insertMessage(long conversationId, long seq, long sender, UUID clientMsgId, String type, String body,
                               Long mediaId, Instant at, boolean withDedup) {
        db.jdbc().sql("""
                        INSERT INTO messages (conversation_id, seq, sender_id, client_msg_id, type, body, media_id, created_at)
                        VALUES (:c, :seq, :s, :m, :type, :body, :media, :at)""")
                .param("c", conversationId).param("seq", seq).param("s", sender).param("m", clientMsgId)
                .param("type", type).param("body", body).param("media", mediaId).param("at", ts(at))
                .update();
        if (withDedup) {
            db.jdbc().sql("""
                            INSERT INTO message_dedup (conversation_id, sender_id, client_msg_id, seq, created_at)
                            VALUES (:c, :s, :m, :seq, :at)""")
                    .param("c", conversationId).param("s", sender).param("m", clientMsgId).param("seq", seq)
                    .param("at", ts(at)).update();
        }
    }

    /** Tarik pesan sendiri ≤ 10 menit setelah dikirim. Isi tetap disimpan untuk bukti report, tidak pernah dikirim lagi. */
    void retract(long me, long conversationId, long seq) {
        memberConversation(me, conversationId);
        var msg = db.jdbc().sql("SELECT " + ChatRows.Message.COLUMNS + " FROM messages WHERE conversation_id = :c AND seq = :s")
                .param("c", conversationId).param("s", seq).query((rs, n) -> ChatRows.Message.map(rs)).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (msg.senderId() != me || "SYSTEM".equals(msg.type())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (msg.retractedAt() != null) {
            return;
        }
        if (msg.createdAt().plus(RETRACT_WINDOW).isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.RETRACT_EXPIRED);
        }
        db.tx().executeWithoutResult(s -> {
            int n = db.jdbc().sql("""
                            UPDATE messages SET deleted_for_all_at = now()
                            WHERE conversation_id = :c AND seq = :s AND created_at = :at AND deleted_for_all_at IS NULL""")
                    .param("c", conversationId).param("s", seq).param("at", ts(msg.createdAt())).update();
            if (n == 1) {
                outbox.append("message.retracted", Long.toString(conversationId),
                        Map.of("conversation_id", conversationId, "seq", seq));
            }
        });
        kick();
    }

    /**
     * {@code msg.delivered} / {@code msg.read}: naikkan penanda (tidak pernah turun, maksimal {@code last_seq}),
     * lalu kirim {@code msg.status} ke lawan bicara (langsung lewat Redis, tidak lewat Kafka).
     */
    void receipts(long me, long conversationId, Long deliveredUpTo, Long readUpTo) {
        var c = memberConversation(me, conversationId);
        long delivered = Math.max(deliveredUpTo == null ? 0 : deliveredUpTo, readUpTo == null ? 0 : readUpTo);
        long read = readUpTo == null ? 0 : readUpTo;
        var row = db.jdbc().sql("""
                        UPDATE conversation_members SET
                               last_delivered_seq = GREATEST(last_delivered_seq, LEAST(:d, :last)),
                               last_read_seq = GREATEST(last_read_seq, LEAST(:r, :last))
                        WHERE conversation_id = :c AND user_id = :me
                        RETURNING last_delivered_seq, last_read_seq""")
                .param("d", delivered).param("r", read).param("last", c.lastSeq())
                .param("c", conversationId).param("me", me)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        var d = new LinkedHashMap<String, Object>();
        d.put("conversation_id", Tsid.toString(conversationId));
        d.put("delivered_up_to", row[0]);
        d.put("read_up_to", row[1]);
        realtime.publish(List.of(c.peerOf(me)), u -> WsFrame.of(FrameType.MSG_STATUS, null, d));
    }

    // ---------------------------------------------------------------- bantu

    /** Percakapan yang boleh dilihat {@code me}: anggota dan belum keluar. Selain itu {@code NOT_FOUND}. */
    Conversation memberConversation(long me, long conversationId) {
        return db.jdbc().sql("SELECT " + Conversation.COLUMNS + """
                         FROM conversations c JOIN conversation_members m ON m.conversation_id = c.id
                        WHERE c.id = :id AND m.user_id = :me AND m.left_at IS NULL""")
                .param("id", conversationId).param("me", me)
                .query((rs, n) -> Conversation.map(rs)).optional()
                .filter(c -> !(c.status() == Status.DECLINED && c.recipientId() == me))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private AccountStanding standing(long userId) {
        return standings.get(userId, id -> {
            var s = identity.findStandings(List.of(id)).get(id);
            return s != null ? s : new AccountStanding(AccountStatus.ACTIVE, Instant.now(), 50);
        });
    }

    private void kick() {
        var p = pump.getIfAvailable();
        if (p != null) {
            p.kick();
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    static OffsetDateTime ts(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
