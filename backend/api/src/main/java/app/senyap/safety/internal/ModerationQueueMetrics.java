package app.senyap.safety.internal;

import app.senyap.shared.db.ModuleDatabase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Antrean moderasi per prioritas (FS-07, NFR-18): {@code senyap_moderation_queue_open{priority="0|1|2"}} dan
 * {@code senyap_moderation_queue_oldest_seconds{priority}}. Alarm P0: umur tertua &gt; 30 menit
 * (docs/05-prosedur-eskalasi-p0.md). Hanya angka, tanpa isi laporan.
 */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class ModerationQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(ModerationQueueMetrics.class);

    private final ModuleDatabase db;
    private final AtomicLong[] open = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
    private final AtomicLong[] oldest = {new AtomicLong(), new AtomicLong(), new AtomicLong()};

    ModerationQueueMetrics(@Qualifier("safetyDatabase") ModuleDatabase db, MeterRegistry meters) {
        this.db = db;
        for (int p = 0; p < 3; p++) {
            Gauge.builder("senyap.moderation.queue.open", open[p], AtomicLong::get).tag("priority", Integer.toString(p))
                    .description("Laporan OPEN di antrean moderator").register(meters);
            Gauge.builder("senyap.moderation.queue.oldest", oldest[p], AtomicLong::get)
                    .tag("priority", Integer.toString(p)).baseUnit("seconds")
                    .description("Umur laporan OPEN tertua").register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${senyap.metrics.backlog-interval:15s}", initialDelayString = "5s")
    void refresh() {
        try {
            long[] n = new long[3];
            long[] age = new long[3];
            db.jdbc().sql("""
                            SELECT priority, count(*) AS n,
                                   COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::bigint AS age
                            FROM reports WHERE status = 'OPEN' GROUP BY priority""")
                    .query(rs -> {
                        int p = rs.getInt("priority");
                        if (p >= 0 && p < 3) {
                            n[p] = rs.getLong("n");
                            age[p] = rs.getLong("age");
                        }
                    });
            for (int p = 0; p < 3; p++) {
                open[p].set(n[p]);
                oldest[p].set(age[p]);
            }
        } catch (RuntimeException e) {
            log.warn("Metrik antrean moderasi gagal: {}", e.getClass().getSimpleName());
        }
    }
}
