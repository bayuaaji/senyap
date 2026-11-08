package app.senyap.shared.outbox;

import app.senyap.contracts.events.EventEnvelope;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.observability.Traces;
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Menulis event ke tabel {@code <modul>.outbox} dalam transaksi yang sedang berjalan (aturan M7).
 * Relay ({@link OutboxRelay}) yang mengirimnya ke Kafka. Wajib dipanggil di dalam transaksi modul
 * yang sama dengan perubahan datanya, supaya simpan + publish atomik.
 */
public final class OutboxWriter {

    private final ModuleDatabase db;
    private final String topic;
    private final Tsid tsid;
    private final JsonMapper json;

    public OutboxWriter(ModuleDatabase db, String topic, Tsid tsid, JsonMapper json) {
        this.db = db;
        this.topic = topic;
        this.tsid = tsid;
        this.json = json;
    }

    /**
     * @param key kunci partisi Kafka (functional spec §5), misalnya {@code conversation_id}
     * @return event yang ditulis
     */
    public EventEnvelope append(String type, String key, Map<String, Object> payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter harus dipanggil di dalam transaksi modul " + db.schema());
        }
        var event = EventEnvelope.of(type, payload).withTraceparent(Traces.currentTraceparent());
        db.jdbc().sql("""
                        INSERT INTO outbox (id, event_id, topic, event_key, event_type, envelope, created_at)
                        VALUES (:id, :eventId, :topic, :key, :type, CAST(:envelope AS jsonb), :createdAt)""")
                .param("id", tsid.next())
                .param("eventId", event.eventId())
                .param("topic", topic)
                .param("key", key)
                .param("type", type)
                .param("envelope", json.writeValueAsString(event))
                .param("createdAt", java.sql.Timestamp.from(event.occurredAt()))
                .update();
        return event;
    }

    public String topic() {
        return topic;
    }
}
