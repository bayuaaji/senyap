package app.senyap.contracts.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope semua event di Kafka: {@code {event_id, type, version, occurred_at, payload}}
 * (functional spec §5). Consumer wajib idempotent berdasarkan {@code event_id}.
 * Isi pesan DM tidak pernah masuk ke payload.
 */
public record EventEnvelope(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("type") String type,
        @JsonProperty("version") int version,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("payload") Map<String, Object> payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (version < 1) {
            throw new IllegalArgumentException("version harus >= 1");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static EventEnvelope of(String type, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), payload);
    }
}
