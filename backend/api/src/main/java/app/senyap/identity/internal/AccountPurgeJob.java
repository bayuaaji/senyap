package app.senyap.identity.internal;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.outbox.OutboxWriter;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purge akun {@code DELETED} setelah masa tenggang (functional spec FS-01: purge dalam 30 hari). Selama
 * masa tenggang, login ulang membatalkan penghapusan ({@code deleted_at} dikosongkan, jadi tidak terpilih di sini).
 * {@code phone_hmac} diganti byte acak (nomor HP tidak lagi terhubung, dan nomor itu bisa mendaftar
 * akun baru), {@code home_geohash} dihapus, lalu event {@code user.purged} diterbitkan. Modul pemilik
 * konten (posts, chat) meng-anonimkan konten user itu menjadi "[dihapus]" saat menerima event tersebut
 * (consumer dibuat di Fase 2/3). Alias + {@code alias_history} dipertahankan untuk moderasi; alias lama
 * tidak dilepas supaya tidak bisa dipakai orang lain untuk menyamar.
 */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class AccountPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeJob.class);
    private static final int BATCH = 100;

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final IdentityProperties props;
    private final SecureRandom random = new SecureRandom();

    AccountPurgeJob(@Qualifier("identityDatabase") ModuleDatabase db, @Qualifier("identityOutbox") OutboxWriter outbox,
                    IdentityProperties props) {
        this.db = db;
        this.outbox = outbox;
        this.props = props;
    }

    @Scheduled(initialDelayString = "${senyap.identity.purge-interval:1h}",
            fixedDelayString = "${senyap.identity.purge-interval:1h}")
    void purgeDue() {
        int total = 0;
        int n;
        do {
            n = purgeBatch();
            total += n;
        } while (n == BATCH);
        if (total > 0) {
            log.info("Purge {} akun yang dihapus", total);
        }
    }

    int purgeBatch() {
        var cutoff = Timestamp.from(Instant.now().minus(props.purgeGrace()));
        Integer purged = db.tx().execute(s -> {
            List<Long> ids = db.jdbc().sql("""
                            SELECT id FROM users
                            WHERE status = 'DELETED' AND purged_at IS NULL AND deleted_at <= :cutoff
                            ORDER BY deleted_at LIMIT :batch FOR UPDATE SKIP LOCKED""")
                    .param("cutoff", cutoff).param("batch", BATCH)
                    .query(Long.class).list();
            for (long id : ids) {
                byte[] tombstone = new byte[32];
                random.nextBytes(tombstone);
                db.jdbc().sql("UPDATE users SET phone_hmac = :t, home_geohash = NULL, purged_at = now() WHERE id = :id")
                        .param("t", tombstone).param("id", id)
                        .update();
                outbox.append("user.purged", Long.toString(id), Map.of("user_id", id));
            }
            return ids.size();
        });
        return purged == null ? 0 : purged;
    }
}
