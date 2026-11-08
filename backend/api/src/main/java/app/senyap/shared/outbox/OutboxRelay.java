package app.senyap.shared.outbox;

import app.senyap.shared.db.ModuleDatabase;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Relay satu modul: mengambil baris {@code outbox} yang belum terkirim ({@code FOR UPDATE SKIP LOCKED},
 * aman dijalankan beberapa worker sekaligus), mengirimnya ke Kafka, lalu menandai {@code published_at}.
 * Pengiriman at-least-once; consumer wajib idempotent berdasarkan {@code event_id}.
 */
public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final ModuleDatabase db;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;

    public OutboxRelay(ModuleDatabase db, KafkaTemplate<String, String> kafka, int batchSize) {
        this.db = db;
        this.kafka = kafka;
        this.batchSize = batchSize;
    }

    private record Row(long id, String topic, String key, String envelope) {
    }

    /** @return jumlah event yang terkirim */
    public int relayOnce() {
        Integer sent = db.tx().execute(status -> {
            List<Row> rows = db.jdbc().sql("""
                            SELECT id, topic, event_key, envelope::text AS envelope FROM outbox
                            WHERE published_at IS NULL
                            ORDER BY id
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED""")
                    .param("limit", batchSize)
                    .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("topic"), rs.getString("event_key"),
                            rs.getString("envelope")))
                    .list();
            int ok = 0;
            for (Row row : rows) {
                try {
                    kafka.send(row.topic(), row.key(), row.envelope()).get(10, TimeUnit.SECONDS);
                    db.jdbc().sql("UPDATE outbox SET published_at = now(), attempts = attempts + 1 WHERE id = :id")
                            .param("id", row.id()).update();
                    ok++;
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("Relay outbox {} gagal untuk id={}: {}", db.schema(), row.id(), e.getClass().getSimpleName());
                    db.jdbc().sql("UPDATE outbox SET attempts = attempts + 1, last_error = :err WHERE id = :id")
                            .param("err", e.getClass().getName()).param("id", row.id()).update();
                    break; // pertahankan urutan per modul; coba lagi di putaran berikutnya
                }
            }
            return ok;
        });
        return sent == null ? 0 : sent;
    }

    public String schema() {
        return db.schema();
    }
}
