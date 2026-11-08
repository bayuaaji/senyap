package app.senyap.safety.internal;

import app.senyap.identity.AccountStatus;
import app.senyap.identity.IdentityApi;
import app.senyap.identity.Sanction;
import app.senyap.identity.TrustAdjustment;
import app.senyap.safety.ContentRef;
import app.senyap.safety.ContentType;
import app.senyap.safety.ImageVerdict;
import app.senyap.safety.TextScreening;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Worker safety (FS-08), idempotent per {@code event_id} ({@code safety.consumed_events}):
 * <ul>
 *   <li>{@code post.created}/{@code reply.created}/{@code message.created}/{@code conversation.requested} →
 *       klasifikasi teks dari DB (isi tidak ada di event) → item antrean AUTO bila FLAG/HOLD (DM: asinkron);</li>
 *   <li>{@code media.rejected} dengan alasan {@code KNOWN_HASH} → laporan P0 + akun {@code SUSPENDED};</li>
 *   <li>sinyal trust: diblokir (−5, sekali per pasangan), request Bisik ditolak (−2), diterima (+1).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class SafetyEventConsumer {

    static final String CONSUMER = "safety-moderation";
    static final int TRUST_BLOCKED = -5;
    static final int TRUST_DECLINED = -2;
    static final int TRUST_ACCEPTED = 1;

    private final ModuleDatabase db;
    private final JsonMapper json;
    private final ContentDirectories contents;
    private final ScreeningService screening;
    private final ReportService reports;
    private final IdentityApi identity;
    private final Tsid tsid;

    SafetyEventConsumer(@Qualifier("safetyDatabase") ModuleDatabase db, JsonMapper json, ContentDirectories contents,
                        ScreeningService screening, ReportService reports, IdentityApi identity, Tsid tsid) {
        this.db = db;
        this.json = json;
        this.contents = contents;
        this.screening = screening;
        this.reports = reports;
        this.identity = identity;
        this.tsid = tsid;
    }

    @KafkaListener(id = CONSUMER + "-posts", groupId = CONSUMER, topics = "${senyap.posts.topic:posts.events}")
    void onPosts(String value) {
        handle(value, (type, p) -> switch (type) {
            case "post.created" -> screen(ContentRef.of(ContentType.POST, p.get("post_id").asLong()));
            case "reply.created" -> screen(ContentRef.of(ContentType.REPLY, p.get("reply_id").asLong()));
            default -> null;
        });
    }

    @KafkaListener(id = CONSUMER + "-chat", groupId = CONSUMER, topics = "${senyap.chat.topic:chat.events}")
    void onChat(String value) {
        handle(value, (type, p) -> switch (type) {
            case "message.created" -> screen(new ContentRef(ContentType.MESSAGE, p.get("conversation_id").asLong(),
                    p.get("seq").asLong()));
            case "conversation.requested" -> screen(new ContentRef(ContentType.MESSAGE,
                    p.get("conversation_id").asLong(), 1));
            case "conversation.declined" -> {
                if ("DECLINED".equals(p.path("reason").asString())) {
                    identity.adjustTrust(List.of(new TrustAdjustment(p.get("initiator_id").asLong(), TRUST_DECLINED,
                            "declined:" + p.get("conversation_id").asLong())));
                }
                yield null;
            }
            case "conversation.accepted" -> {
                identity.adjustTrust(List.of(new TrustAdjustment(p.get("initiator_id").asLong(), TRUST_ACCEPTED,
                        "accepted:" + p.get("conversation_id").asLong())));
                yield null;
            }
            default -> null;
        });
    }

    @KafkaListener(id = CONSUMER + "-media", groupId = CONSUMER, topics = "${senyap.media.topic:media.events}")
    void onMedia(String value) {
        handle(value, (type, p) -> {
            if (!"media.rejected".equals(type) || !ImageVerdict.KNOWN_HASH.equals(p.path("reason").asString())) {
                return null;
            }
            long mediaId = p.get("media_id").asLong();
            long owner = p.get("owner_id").asLong();
            // Gambar tidak ditampilkan ke moderator (bukan URL): hanya fakta kecocokan.
            var doc = new SnapshotDoc("Gambar unggahan cocok dengan daftar hash materi terlarang (media "
                    + Tsid.toString(mediaId) + "). Ikuti prosedur eskalasi P0.", null, "REJECTED", 0, 0,
                    Instant.now(), List.of());
            identity.applySanction(owner, new Sanction(AccountStatus.SUSPENDED, null));
            return new Auto(ContentRef.of(ContentType.MEDIA, mediaId), owner, doc, List.of("known_hash"), 0,
                    "minor_safety", "SUSPEND");
        });
    }

    @KafkaListener(id = CONSUMER + "-safety", groupId = CONSUMER, topics = "${senyap.safety.topic:safety.events}")
    void onSafety(String value) {
        handle(value, (type, p) -> {
            if ("user.blocked".equals(type)) {
                long blocker = p.get("blocker_id").asLong();
                long blocked = p.get("blocked_id").asLong();
                identity.adjustTrust(List.of(new TrustAdjustment(blocked, TRUST_BLOCKED,
                        "blocked:" + blocker + ":" + blocked)));
            }
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------

    /** Item antrean otomatis + (opsional) aksi sistem yang dicatat di moderation_actions. */
    private record Auto(ContentRef ref, long owner, SnapshotDoc doc, List<String> labels, int priority, String reason,
                        String autoAction) {
    }

    @FunctionalInterface
    private interface Handler {
        Auto apply(String type, JsonNode payload);
    }

    private void handle(String value, Handler handler) {
        JsonNode event = json.readTree(value);
        UUID eventId = UUID.fromString(event.get("event_id").asString());
        if (consumed(eventId)) {
            return;
        }
        Auto auto = handler.apply(event.get("type").asString(), event.get("payload"));
        db.tx().executeWithoutResult(s -> {
            int n = db.jdbc().sql("""
                            INSERT INTO consumed_events (consumer, event_id) VALUES (:c, :e) ON CONFLICT DO NOTHING""")
                    .param("c", CONSUMER).param("e", eventId).update();
            if (n == 0 || auto == null) {
                return;
            }
            var reportId = reports.createAutoInTx(auto.ref(), auto.owner(), auto.doc(), auto.labels(), auto.priority(),
                    auto.reason());
            if (auto.autoAction() != null) {
                db.jdbc().sql("""
                                INSERT INTO moderation_actions (id, target_type, target_id, user_id, action, reason, actor,
                                                                report_id)
                                VALUES (:id, :t, :tid, :u, :a, :why, 'AUTO', :rid)""")
                        .param("id", tsid.next()).param("t", auto.ref().type().name()).param("tid", auto.ref().id())
                        .param("u", auto.owner()).param("a", auto.autoAction()).param("why", String.join(",", auto.labels()))
                        .param("rid", reportId.orElse(null))
                        .update();
            }
        });
    }

    /** Klasifikasi teks konten yang sudah tersimpan; FLAG/HOLD → item antrean AUTO. */
    private Auto screen(ContentRef ref) {
        var snap = contents.snapshot(ref, null).orElse(null);
        if (snap == null || snap.text() == null || snap.text().isBlank()) {
            return null;
        }
        ContentType kind = ref.type() == ContentType.MESSAGE ? ContentType.MESSAGE : ref.type();
        var verdict = screening.screenText(new TextScreening(kind, snap.text()));
        if (!verdict.queued()) {
            return null;
        }
        String reason = verdict.labels().stream().min((a, b) -> Integer.compare(Priorities.of(a), Priorities.of(b)))
                .orElse("other");
        return new Auto(ref, snap.ownerUserId(), SnapshotDoc.of(snap, null), verdict.labels(), verdict.priority(),
                Priorities.REASONS.contains(reason) ? reason : "other", null);
    }

    private boolean consumed(UUID eventId) {
        return db.jdbc().sql("SELECT 1 FROM consumed_events WHERE consumer = :c AND event_id = :e")
                .param("c", CONSUMER).param("e", eventId).query(Integer.class).optional().isPresent();
    }
}
