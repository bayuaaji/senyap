package app.senyap.safety;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * API untuk konsol moderator (modul admin, FS-09). Moderator tidak pernah melihat nomor HP atau user ID; akun
 * ditampilkan sebagai {@code account_ref} acak per kasus. Penelusuran identitas hanya lewat
 * {@link #lookupIdentity} (peran legal, alasan wajib, tercatat di audit log).
 */
public interface ModerationConsoleApi {

    /** Antrean kasus terbuka, P0 dulu lalu terlama. {@code afterReportId} = cursor (ID terakhir halaman lalu). */
    List<QueueItem> queue(Integer priority, String status, Long afterReportId, int limit);

    Optional<CaseDetail> caseDetail(long reportId, String moderator);

    /** @throws IllegalArgumentException bila alasan kosong atau aksi tidak berlaku untuk kasus ini */
    void act(long reportId, ModeratorAction action, String reason, String moderator);

    List<AuditEntry> audit(Long beforeId, int limit);

    /** Hanya peran legal. Alasan wajib (mis. nomor surat permintaan aparat). Tercatat di audit log. */
    Optional<IdentityDisclosure> lookupIdentity(long reportId, String reason, String moderator);

    record QueueItem(long reportId, int priority, String status, String source, String targetType, String reason,
                     String excerpt, int reportsOnTarget, Instant createdAt) {
    }

    record CaseDetail(long reportId, int priority, String status, String source, String targetType, String targetId,
                      String reason, String note, List<String> labels, String accountRef, String text,
                      String imageUrl, String visibility, List<CaseMessage> context, List<HistoryEntry> history,
                      List<ActionEntry> actions, int reportsOnTarget, boolean contentModeratable, Instant createdAt,
                      String resolution) {
    }

    /** {@code who}: {@code TERLAPOR}, {@code PELAPOR}, atau {@code SISTEM}. */
    record CaseMessage(long seq, String who, String type, String body, boolean retracted, boolean reported,
                       Instant createdAt) {
    }

    /** Riwayat pelanggaran akun yang sama di kasus lain, tanpa rujukan yang bisa menghubungkan kasus. */
    record HistoryEntry(String action, String reason, Instant createdAt) {
    }

    record ActionEntry(String action, String reason, String actor, Instant createdAt) {
    }

    record AuditEntry(long id, String actor, String action, Long reportId, String detail, Instant createdAt) {
    }

    record IdentityDisclosure(String accountRef, long userId, String publicId, String alias, String status,
                              int trustScore, String phoneHmacHex, Instant createdAt, List<String> aliasHistory) {
    }
}
