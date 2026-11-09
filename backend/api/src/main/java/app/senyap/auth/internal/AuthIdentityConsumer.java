package app.senyap.auth.internal;

import app.senyap.shared.db.ModuleDatabase;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer {@code user.purged} dari {@code identity.events}: kredensial email (alamat + hash kata sandi + kode
 * reset) akun yang di-purge dihapus, supaya email tidak lagi terhubung ke akun dan bisa mendaftar akun baru.
 * Idempotent lewat {@code consumed_events}.
 */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class AuthIdentityConsumer {

    private final ModuleDatabase db;
    private final JsonMapper json;

    AuthIdentityConsumer(@Qualifier("authDatabase") ModuleDatabase db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    @KafkaListener(id = "auth-identity-consumer", groupId = "senyap-auth",
            topics = "${senyap.identity.topic:identity.events}")
    void onIdentityEvent(String envelope) {
        JsonNode node = json.readTree(envelope);
        if (!"user.purged".equals(node.path("type").asString())) {
            return;
        }
        UUID eventId = UUID.fromString(node.path("event_id").asString());
        long userId = node.path("payload").path("user_id").asLong();
        db.tx().executeWithoutResult(s -> {
            int fresh = db.jdbc().sql("INSERT INTO consumed_events (event_id) VALUES (:e) ON CONFLICT DO NOTHING")
                    .param("e", eventId).update();
            if (fresh == 1) {
                db.jdbc().sql("DELETE FROM email_credentials WHERE user_id = :u").param("u", userId).update();
            }
        });
    }
}
