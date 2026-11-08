package app.senyap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.outbox.OutboxWriter;
import app.senyap.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.json.JsonMapper;

/** Outbox per modul (M7): ditulis di transaksi modul, relay worker mengirim ke topik modul di Redpanda. */
class OutboxRelayIT extends IntegrationTest {

    @Autowired
    @Qualifier("chatDatabase")
    ModuleDatabase chat;

    @Autowired
    @Qualifier("chatOutbox")
    OutboxWriter chatOutbox;

    @Autowired
    JsonMapper json;

    @Test
    void eventDariOutboxSampaiDiKafkaDenganEnvelopeDanKunciPartisi() {
        var event = chat.tx().execute(s ->
                chatOutbox.append("message.created", "4242", Map.of("conversation_id", "4242", "seq", 7)));

        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("chat.events"));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var found = false;
                for (var rec : consumer.poll(Duration.ofMillis(500))) {
                    var node = json.readTree(rec.value());
                    if (node.get("event_id").asString().equals(event.eventId().toString())) {
                        assertThat(rec.key()).isEqualTo("4242");
                        assertThat(node.get("type").asString()).isEqualTo("message.created");
                        assertThat(node.get("payload").get("seq").asInt()).isEqualTo(7);
                        assertThat(node.has("occurred_at")).isTrue();
                        found = true;
                    }
                }
                assertThat(found).isTrue();
            });
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> chat.jdbc()
                .sql("SELECT published_at IS NOT NULL FROM outbox WHERE event_id = :e")
                .param("e", event.eventId()).query(Boolean.class).single());
    }

    @Test
    void menulisOutboxDiLuarTransaksiDitolak() {
        assertThatThrownBy(() -> chatOutbox.append("message.created", "1", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
