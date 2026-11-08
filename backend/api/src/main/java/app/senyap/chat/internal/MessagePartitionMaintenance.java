package app.senyap.chat.internal;

import app.senyap.shared.db.ModuleDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Memastikan partisi bulanan {@code chat.messages} tersedia untuk bulan ini + N bulan ke depan.
 * Migrasi V1 sudah membuat 4 bulan pertama; job ini (profil worker) menjaga sisanya.
 */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class MessagePartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(MessagePartitionMaintenance.class);

    private final ModuleDatabase db;
    private final int monthsAhead;

    MessagePartitionMaintenance(@Qualifier("chatDatabase") ModuleDatabase db,
                                @org.springframework.beans.factory.annotation.Value("${senyap.chat.partitions.months-ahead:3}")
                                int monthsAhead) {
        this.db = db;
        this.monthsAhead = monthsAhead;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${senyap.chat.partitions.check-interval:6h}")
    void ensurePartitions() {
        Integer created = db.jdbc().sql("SELECT ensure_message_partitions(current_date, :ahead)")
                .param("ahead", monthsAhead)
                .query(Integer.class)
                .single();
        if (created != null && created > 0) {
            log.info("Membuat {} partisi baru chat.messages", created);
        }
    }
}
