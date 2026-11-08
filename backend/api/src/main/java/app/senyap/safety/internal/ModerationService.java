package app.senyap.safety.internal;

import app.senyap.identity.AccountStatus;
import app.senyap.identity.IdentityApi;
import app.senyap.identity.Sanction;
import app.senyap.identity.TrustAdjustment;
import app.senyap.notifications.NotificationsApi;
import app.senyap.notifications.NotificationsApi.Push;
import app.senyap.safety.ContentRef;
import app.senyap.safety.ContentType;
import app.senyap.safety.ContentVisibility;
import app.senyap.safety.ModerationConsoleApi;
import app.senyap.safety.ModeratorAction;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Konsol moderator (FS-09): antrean per prioritas, detail kasus dengan snapshot, aksi, audit log. Satu aksi
 * menyelesaikan semua laporan OPEN atas konten yang sama, dan pelapornya menerima notifikasi tanpa detail sanksi.
 * Efek di modul lain (identity, konten, push) dijalankan dulu; jejaknya dicatat di satu transaksi {@code safetyTx}.
 */
@Service
class ModerationService implements ModerationConsoleApi {

    static final int TRUST_HIDE = -10;
    static final int TRUST_WARN = -5;
    static final int TRUST_RESTRICT = -15;

    private record Report(long id, Long reporterId, String targetType, long targetId, long targetSeq,
                          Long targetUserId, String reason, String note, SnapshotDoc snapshot, int priority,
                          String status, String source, List<String> labels, String resolution, Instant createdAt) {
    }

    private final ModuleDatabase db;
    private final Tsid tsid;
    private final JsonMapper json;
    private final IdentityApi identity;
    private final NotificationsApi notifications;
    private final ContentDirectories contents;
    private final byte[] refSecret;

    ModerationService(@Qualifier("safetyDatabase") ModuleDatabase db, Tsid tsid, JsonMapper json, IdentityApi identity,
                      NotificationsApi notifications, ContentDirectories contents, SafetyProperties props) {
        this.db = db;
        this.tsid = tsid;
        this.json = json;
        this.identity = identity;
        this.notifications = notifications;
        this.contents = contents;
        if (props.accountRefSecret().isBlank()) {
            this.refSecret = new byte[32];
            new SecureRandom().nextBytes(refSecret);
        } else {
            this.refSecret = props.accountRefSecret().getBytes(StandardCharsets.UTF_8);
        }
    }

    // ---- antrean -------------------------------------------------------------------------------

    @Override
    public List<QueueItem> queue(Integer priority, String status, Long afterReportId, int limit) {
        String st = status == null || status.isBlank() ? "OPEN" : status;
        var sql = new StringBuilder("""
                SELECT r.*, (SELECT count(*) FROM reports o WHERE o.target_type = r.target_type
                               AND o.target_id = r.target_id AND o.target_seq = r.target_seq) AS on_target
                FROM reports r WHERE r.status = :st""");
        if (priority != null) {
            sql.append(" AND r.priority = :p");
        }
        if (afterReportId != null) {
            // cursor (priority, id): lanjut setelah baris terakhir halaman sebelumnya
            sql.append(" AND (r.priority, r.id) > (SELECT priority, id FROM reports WHERE id = :after)");
        }
        sql.append(" ORDER BY r.priority, r.id LIMIT :n");
        var spec = db.jdbc().sql(sql.toString()).param("st", st).param("n", Math.clamp(limit, 1, 200));
        if (priority != null) {
            spec = spec.param("p", priority);
        }
        if (afterReportId != null) {
            spec = spec.param("after", afterReportId);
        }
        return spec.query((rs, n) -> {
            var r = map(rs);
            return new QueueItem(r.id(), r.priority(), r.status(), r.source(), r.targetType(), r.reason(),
                    excerpt(r.snapshot()), rs.getInt("on_target"), r.createdAt());
        }).list();
    }

    // ---- detail --------------------------------------------------------------------------------

    @Override
    public Optional<CaseDetail> caseDetail(long reportId, String moderator) {
        var found = find(reportId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var r = found.get();
        audit(moderator, "VIEW_CASE", reportId, null);
        String ref = r.targetUserId() == null ? "-" : accountRef(reportId, r.targetUserId());
        var history = new ArrayList<HistoryEntry>();
        if (r.targetUserId() != null) {
            db.jdbc().sql("""
                            SELECT a.action, a.reason, a.created_at FROM moderation_actions a
                            WHERE a.user_id = :u AND (a.report_id IS NULL OR a.report_id NOT IN (
                                SELECT id FROM reports WHERE target_type = :t AND target_id = :tid AND target_seq = :seq))
                            ORDER BY a.created_at DESC LIMIT 50""")
                    .param("u", r.targetUserId()).param("t", r.targetType()).param("tid", r.targetId())
                    .param("seq", r.targetSeq())
                    .query((rs, n) -> new HistoryEntry(rs.getString(1), rs.getString(2),
                            rs.getTimestamp(3).toInstant()))
                    .list().forEach(history::add);
        }
        var actions = db.jdbc().sql("""
                        SELECT action, reason, actor, created_at FROM moderation_actions
                        WHERE target_type = :t AND target_id = :tid AND report_id IN (
                            SELECT id FROM reports WHERE target_type = :t AND target_id = :tid AND target_seq = :seq)
                        ORDER BY created_at""")
                .param("t", r.targetType()).param("tid", r.targetId()).param("seq", r.targetSeq())
                .query((rs, n) -> new ActionEntry(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
                .list();
        int onTarget = db.jdbc().sql("""
                        SELECT count(*) FROM reports WHERE target_type = :t AND target_id = :tid AND target_seq = :seq""")
                .param("t", r.targetType()).param("tid", r.targetId()).param("seq", r.targetSeq())
                .query(Integer.class).single();
        var s = r.snapshot();
        var context = s.context().stream().map(m -> new CaseMessage(m.seq(), m.who(), m.type(), m.body(),
                m.retracted(), m.seq() == s.reportedSeq() && "MESSAGE".equals(r.targetType()), m.createdAt())).toList();
        String targetId = "MESSAGE".equals(r.targetType())
                ? Tsid.toString(r.targetId()) + "#" + r.targetSeq() : Tsid.toString(r.targetId());
        boolean moderatable = "POST".equals(r.targetType()) || "REPLY".equals(r.targetType());
        return Optional.of(new CaseDetail(r.id(), r.priority(), r.status(), r.source(), r.targetType(), targetId,
                r.reason(), r.note(), r.labels(), ref, s.text(), s.imageUrl(), s.visibility(), context, history,
                actions, onTarget, moderatable, r.createdAt(), r.resolution()));
    }

    // ---- aksi ----------------------------------------------------------------------------------

    @Override
    public void act(long reportId, ModeratorAction action, String reason, String moderator) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Alasan wajib diisi.");
        }
        String why = reason.strip().length() > 200 ? reason.strip().substring(0, 200) : reason.strip();
        var r = find(reportId).orElseThrow(() -> new IllegalArgumentException("Kasus tidak ditemukan."));
        var ref = new ContentRef(ContentType.valueOf(r.targetType()), r.targetId(), r.targetSeq());
        Long target = r.targetUserId();
        boolean needsUser = switch (action) {
            case WARN, RESTRICT_24H, RESTRICT_7D, SUSPEND, BAN -> true;
            default -> false;
        };
        if (needsUser && target == null) {
            throw new IllegalArgumentException("Kasus ini tidak terhubung ke akun.");
        }
        Instant now = Instant.now();
        Instant expires = null;
        Integer trust = null;
        switch (action) {
            case HIDE_CONTENT -> {
                if (!contents.moderate(ref, ContentVisibility.HIDDEN)) {
                    throw new IllegalArgumentException("Konten ini tidak bisa disembunyikan.");
                }
                trust = TRUST_HIDE;
            }
            case RESTORE_CONTENT -> {
                if (!contents.moderate(ref, ContentVisibility.PUBLIC)) {
                    throw new IllegalArgumentException("Konten ini tidak bisa dipulihkan.");
                }
            }
            case WARN -> {
                notifications.push(List.of(new Push(target, "MODERATION_WARNING", "Peringatan dari Senyap",
                        "Salah satu kontenmu melanggar Pedoman Komunitas. Pelanggaran berikutnya bisa membuat akunmu dibatasi.",
                        "moderation", Map.of())));
                trust = TRUST_WARN;
            }
            case RESTRICT_24H, RESTRICT_7D -> {
                expires = now.plus(action == ModeratorAction.RESTRICT_24H ? Duration.ofHours(24) : Duration.ofDays(7));
                identity.applySanction(target, new Sanction(AccountStatus.RESTRICTED, expires));
                trust = TRUST_RESTRICT;
            }
            case SUSPEND -> {
                identity.applySanction(target, new Sanction(AccountStatus.SUSPENDED, null));
                trust = TRUST_RESTRICT;
            }
            case BAN -> identity.applySanction(target, new Sanction(AccountStatus.BANNED, null));
            case DISMISS -> { }
        }
        long actionId = tsid.next();
        if (trust != null && target != null) {
            identity.adjustTrust(List.of(new TrustAdjustment(target, trust, "action:" + actionId)));
        }
        String newStatus = action == ModeratorAction.DISMISS || action == ModeratorAction.RESTORE_CONTENT
                ? "DISMISSED" : "ACTIONED";
        Instant exp = expires;
        List<Long> toNotify = db.tx().execute(s -> {
            db.jdbc().sql("""
                            INSERT INTO moderation_actions (id, target_type, target_id, user_id, action, reason, actor,
                                                            expires_at, report_id)
                            VALUES (:id, :t, :tid, :u, :a, :why, :actor, :exp, :rid)""")
                    .param("id", actionId).param("t", r.targetType()).param("tid", r.targetId()).param("u", target)
                    .param("a", action.name()).param("why", why).param("actor", moderator)
                    .param("exp", exp == null ? null : java.sql.Timestamp.from(exp)).param("rid", reportId)
                    .update();
            // Semua laporan OPEN atas konten yang sama ikut selesai (plus laporan ini sendiri apa pun statusnya).
            db.jdbc().sql("""
                            UPDATE reports SET status = :st, resolution = :a, resolved_by = :actor, resolved_at = now()
                            WHERE (id = :rid) OR (status = 'OPEN' AND target_type = :t AND target_id = :tid
                                                  AND target_seq = :seq)""")
                    .param("st", newStatus).param("a", action.name()).param("actor", moderator).param("rid", reportId)
                    .param("t", r.targetType()).param("tid", r.targetId()).param("seq", r.targetSeq())
                    .update();
            var reporters = db.jdbc().sql("""
                            UPDATE reports SET reporter_notified_at = now()
                            WHERE reporter_id IS NOT NULL AND reporter_notified_at IS NULL AND status <> 'OPEN'
                              AND target_type = :t AND target_id = :tid AND target_seq = :seq
                            RETURNING reporter_id""")
                    .param("t", r.targetType()).param("tid", r.targetId()).param("seq", r.targetSeq())
                    .query(Long.class).list();
            auditInTx(moderator, "ACTION_" + action.name(), reportId, why);
            return reporters;
        });
        if (toNotify != null && !toNotify.isEmpty()) {
            notifications.push(toNotify.stream().distinct().map(u -> new Push(u, "REPORT_UPDATE",
                    "Laporanmu sudah ditindaklanjuti", "Terima kasih sudah membantu menjaga Senyap.", "report",
                    Map.of())).toList());
        }
    }

    // ---- audit + legal -------------------------------------------------------------------------

    @Override
    public List<AuditEntry> audit(Long beforeId, int limit) {
        return db.jdbc().sql("""
                        SELECT id, actor, action, report_id, detail, created_at FROM moderator_audit
                        WHERE id < :before ORDER BY id DESC LIMIT :n""")
                .param("before", beforeId == null ? Long.MAX_VALUE : beforeId).param("n", Math.clamp(limit, 1, 200))
                .query((rs, n) -> new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, Long.class), rs.getString(5), rs.getTimestamp(6).toInstant()))
                .list();
    }

    @Override
    public Optional<IdentityDisclosure> lookupIdentity(long reportId, String reason, String moderator) {
        if (reason == null || reason.strip().length() < 10) {
            throw new IllegalArgumentException("Alasan penelusuran wajib diisi (min. 10 karakter, mis. nomor surat).");
        }
        var r = find(reportId).orElse(null);
        if (r == null || r.targetUserId() == null) {
            return Optional.empty();
        }
        String why = reason.strip().length() > 450 ? reason.strip().substring(0, 450) : reason.strip();
        audit(moderator, "LEGAL_IDENTITY_LOOKUP", reportId, why);
        var rec = identity.findRecords(List.of(r.targetUserId())).get(r.targetUserId());
        if (rec == null) {
            return Optional.empty();
        }
        return Optional.of(new IdentityDisclosure(accountRef(reportId, rec.userId()), rec.userId(),
                rec.publicId().toString(), rec.alias(), rec.status().name(), rec.trustScore(), rec.phoneHmacHex(),
                rec.createdAt(), rec.aliasHistory()));
    }

    void audit(String actor, String action, Long reportId, String detail) {
        db.tx().executeWithoutResult(s -> auditInTx(actor, action, reportId, detail));
    }

    private void auditInTx(String actor, String action, Long reportId, String detail) {
        db.jdbc().sql("""
                        INSERT INTO moderator_audit (id, actor, action, report_id, detail)
                        VALUES (:id, :actor, :a, :rid, :d)""")
                .param("id", tsid.next()).param("actor", actor).param("a", action).param("rid", reportId)
                .param("d", detail).update();
    }

    // ---- bantu ---------------------------------------------------------------------------------

    /** Rujukan akun acak per kasus: HMAC(rahasia, report_id:user_id). Tidak bisa dihubungkan antar kasus. */
    String accountRef(long reportId, long userId) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(refSecret, "HmacSHA256"));
            byte[] h = mac.doFinal((reportId + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return "AKN-" + HexFormat.of().formatHex(h, 0, 4).toUpperCase();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<Report> find(long reportId) {
        return db.jdbc().sql("SELECT * FROM reports WHERE id = :id").param("id", reportId)
                .query((rs, n) -> map(rs)).optional();
    }

    private Report map(ResultSet rs) throws SQLException {
        var snap = json.readValue(rs.getString("snapshot"), SnapshotDoc.class);
        if (snap.context() == null) {
            snap = new SnapshotDoc(snap.text(), snap.imageUrl(), snap.visibility(), snap.postId(), snap.reportedSeq(),
                    snap.createdAt(), List.of());
        }
        List<String> labels = json.readValue(rs.getString("labels"), new TypeReference<List<String>>() { });
        return new Report(rs.getLong("id"), rs.getObject("reporter_id", Long.class), rs.getString("target_type"),
                rs.getLong("target_id"), rs.getLong("target_seq"), rs.getObject("target_user_id", Long.class),
                rs.getString("reason"), rs.getString("note"), snap, rs.getInt("priority"), rs.getString("status"),
                rs.getString("source"), labels, rs.getString("resolution"), rs.getTimestamp("created_at").toInstant());
    }

    private static String excerpt(SnapshotDoc s) {
        String t = s.text();
        if (t == null || t.isBlank()) {
            var reported = s.context().stream().filter(m -> m.seq() == s.reportedSeq()).findFirst();
            t = reported.map(SnapshotDoc.Msg::body).orElse(s.context().isEmpty() ? "" : s.context().getLast().body());
        }
        if (t == null) {
            return "";
        }
        return t.length() <= 120 ? t : t.substring(0, 119) + "…";
    }

}
