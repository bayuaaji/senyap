package app.senyap.shared.observability;

import app.senyap.shared.db.ModuleDatabase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Metrik antrean yang dibaca dari DB tiap 15 detik (bukan saat scrape, supaya scrape murah):
 * <ul>
 *   <li>{@code senyap_outbox_pending{module}}: event outbox belum terkirim ke Kafka (relay macet / Kafka mati);</li>
 *   <li>{@code senyap_outbox_oldest_seconds{module}}: umur event tertua yang belum terkirim.</li>
 * </ul>
 * Lag consumer Kafka datang dari metrik klien Kafka ({@code kafka_consumer_fetch_manager_records_lag_max}).
 * Hanya di worker (satu sumber angka, tidak dobel per replika api).
 */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class BacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(BacklogMetrics.class);

    private final List<ModuleDatabase> modules;
    private final Map<String, AtomicLong> pending = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> oldest = new ConcurrentHashMap<>();

    BacklogMetrics(List<ModuleDatabase> modules, MeterRegistry meters) {
        this.modules = modules;
        for (var db : modules) {
            String m = db.schema();
            Gauge.builder("senyap.outbox.pending", pending.computeIfAbsent(m, k -> new AtomicLong()), AtomicLong::get)
                    .tag("module", m).description("Event outbox belum terkirim").register(meters);
            Gauge.builder("senyap.outbox.oldest", oldest.computeIfAbsent(m, k -> new AtomicLong()), AtomicLong::get)
                    .tag("module", m).baseUnit("seconds").description("Umur event outbox tertua yang belum terkirim")
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${senyap.metrics.backlog-interval:15s}", initialDelayString = "5s")
    void refresh() {
        for (var db : modules) {
            try {
                var row = db.jdbc().sql("""
                                SELECT count(*) AS n,
                                       COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::bigint AS age
                                FROM outbox WHERE published_at IS NULL""")
                        .query((rs, i) -> new long[] {rs.getLong("n"), rs.getLong("age")}).single();
                pending.get(db.schema()).set(row[0]);
                oldest.get(db.schema()).set(row[1]);
            } catch (RuntimeException e) {
                log.warn("Metrik outbox {} gagal: {}", db.schema(), e.getClass().getSimpleName());
            }
        }
    }
}
