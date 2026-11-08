package app.senyap.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.senyap.contracts.events.EventEnvelope;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void ofMembuatEventIdDanVersiSatu() {
        var e = EventEnvelope.of("message.created", Map.of("conversation_id", "1", "seq", 3));
        assertThat(e.eventId()).isNotNull();
        assertThat(e.version()).isEqualTo(1);
        assertThat(e.payload()).containsEntry("seq", 3);
    }

    @Test
    void versiNolDitolak() {
        assertThatThrownBy(() -> new EventEnvelope(java.util.UUID.randomUUID(), "x.y", 0, java.time.Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
