package app.senyap.safety.internal;

import app.senyap.identity.AccountStanding;
import app.senyap.identity.IdentityApi;
import app.senyap.safety.ContentRef;
import app.senyap.safety.ContentSnapshot;
import app.senyap.safety.ContentType;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.outbox.OutboxWriter;
import app.senyap.shared.ratelimit.RateLimiter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Report (FS-07): snapshot konten saat dilaporkan (DM: 20 pesan di sekitarnya, termasuk yang sudah ditarik),
 * prioritas dari alasan, satu laporan per pelapor per konten. Item antrean otomatis (FS-08) juga disimpan di
 * tabel yang sama dengan {@code source = AUTO}.
 */
@Service
class ReportService {

    record ReportView(@JsonProperty("id") String id, @JsonProperty("status") String status) {
    }

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final Tsid tsid;
    private final ContentDirectories contents;
    private final IdentityApi identity;
    private final RateLimiter rateLimiter;
    private final SafetyProperties props;
    private final JsonMapper json;

    ReportService(@Qualifier("safetyDatabase") ModuleDatabase db, @Qualifier("safetyOutbox") OutboxWriter outbox,
                  Tsid tsid, ContentDirectories contents, IdentityApi identity, RateLimiter rateLimiter,
                  SafetyProperties props, JsonMapper json) {
        this.db = db;
        this.outbox = outbox;
        this.tsid = tsid;
        this.contents = contents;
        this.identity = identity;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.json = json;
    }

    ReportView create(long reporterId, ContentRef ref, String reason, String note) {
        if (ref.type() == ContentType.MEDIA) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Jenis konten tidak dikenal.");
        }
        if (reason == null || !Priorities.REASONS.contains(reason)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Alasan laporan tidak dikenal.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Catatan maksimal 500 karakter.");
        }
        var existing = findExisting(reporterId, ref);
        if (existing.isPresent()) {
            return existing.get();
        }
        var standing = identity.findStandings(List.of(reporterId)).get(reporterId);
        boolean fresh = standing != null && standing.tier(Instant.now()) == AccountStanding.Tier.NEW;
        rateLimiter.hit("safety:report:" + reporterId, fresh ? props.reportsPerDayNewAccount() : props.reportsPerDay(),
                Duration.ofDays(1));

        ContentSnapshot snap = contents.snapshot(ref, reporterId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (snap.ownerUserId() == reporterId) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kamu tidak bisa melaporkan kontenmu sendiri.");
        }
        int priority = Priorities.of(reason);
        long id = tsid.next();
        String doc = json.writeValueAsString(SnapshotDoc.of(snap, reporterId));
        boolean inserted = Boolean.TRUE.equals(db.tx().execute(s -> {
            int n = db.jdbc().sql("""
                            INSERT INTO reports (id, reporter_id, target_type, target_id, target_seq, target_user_id,
                                                 reason, note, snapshot, priority, source)
                            VALUES (:id, :r, :t, :tid, :seq, :owner, :reason, :note, CAST(:snap AS jsonb), :p, 'USER')
                            ON CONFLICT DO NOTHING""")
                    .param("id", id).param("r", reporterId).param("t", ref.type().name()).param("tid", ref.id())
                    .param("seq", ref.seq()).param("owner", snap.ownerUserId()).param("reason", reason)
                    .param("note", cleanNote).param("snap", doc).param("p", priority)
                    .update();
            if (n == 1) {
                outbox.append("report.created", Long.toString(ref.id()), Map.of("report_id", id,
                        "target_type", ref.type().name(), "target_id", ref.id(), "priority", priority));
            }
            return n == 1;
        }));
        if (!inserted) {
            return findExisting(reporterId, ref).orElseThrow(() -> new ApiException(ErrorCode.INTERNAL));
        }
        return new ReportView(Tsid.toString(id), "OPEN");
    }

    private Optional<ReportView> findExisting(long reporterId, ContentRef ref) {
        return db.jdbc().sql("""
                        SELECT id, status FROM reports
                        WHERE reporter_id = :r AND target_type = :t AND target_id = :tid AND target_seq = :seq""")
                .param("r", reporterId).param("t", ref.type().name()).param("tid", ref.id()).param("seq", ref.seq())
                .query((rs, n) -> new ReportView(Tsid.toString(rs.getLong(1)), rs.getString(2))).optional();
    }

    /**
     * Item antrean otomatis. Harus dipanggil di dalam transaksi {@code safetyTx}. Dilewati bila target yang
     * sama sudah punya item AUTO yang masih OPEN. @return ID report, atau kosong bila dilewati
     */
    Optional<Long> createAutoInTx(ContentRef ref, long ownerUserId, SnapshotDoc doc, List<String> labels, int priority,
                                  String reason) {
        boolean open = db.jdbc().sql("""
                        SELECT 1 FROM reports WHERE source = 'AUTO' AND status = 'OPEN'
                          AND target_type = :t AND target_id = :tid AND target_seq = :seq""")
                .param("t", ref.type().name()).param("tid", ref.id()).param("seq", ref.seq())
                .query(Integer.class).optional().isPresent();
        if (open) {
            return Optional.empty();
        }
        long id = tsid.next();
        db.jdbc().sql("""
                        INSERT INTO reports (id, reporter_id, target_type, target_id, target_seq, target_user_id, reason,
                                             snapshot, priority, source, labels)
                        VALUES (:id, NULL, :t, :tid, :seq, :owner, :reason, CAST(:snap AS jsonb), :p, 'AUTO',
                                CAST(:labels AS jsonb))""")
                .param("id", id).param("t", ref.type().name()).param("tid", ref.id()).param("seq", ref.seq())
                .param("owner", ownerUserId).param("reason", reason).param("snap", json.writeValueAsString(doc))
                .param("p", priority).param("labels", json.writeValueAsString(labels))
                .update();
        outbox.append("report.created", Long.toString(ref.id()), Map.of("report_id", id,
                "target_type", ref.type().name(), "target_id", ref.id(), "priority", priority));
        return Optional.of(id);
    }
}
