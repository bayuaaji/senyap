package app.senyap.shared.outbox;

import app.senyap.shared.db.DatabaseBootstrap;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Helper untuk konfigurasi persistence tiap modul, supaya semua modul memakai pola yang sama:
 * {@code senyap.<modul>.datasource.*}, topik {@code senyap.<modul>.topic} (default {@code <modul>.events}).
 */
public final class ModuleInfrastructure {

    private ModuleInfrastructure() {
    }

    public static ModuleDatabase database(String module, Environment env, DatabaseBootstrap bootstrap) {
        return ModuleDatabase.create(module, env, bootstrap);
    }

    public static String topic(String module, Environment env) {
        return env.getProperty("senyap." + module + ".topic", module + ".events");
    }

    public static NewTopic newTopic(String module, Environment env) {
        return TopicBuilder.name(topic(module, env))
                .partitions(env.getProperty("senyap.kafka.default-partitions", Integer.class, 3))
                .replicas(env.getProperty("senyap.kafka.replication-factor", Integer.class, 1))
                .build();
    }

    public static OutboxWriter outboxWriter(String module, ModuleDatabase db, Environment env, Tsid tsid,
                                            JsonMapper json) {
        return new OutboxWriter(db, topic(module, env), tsid, json);
    }

    public static OutboxRelay outboxRelay(ModuleDatabase db, KafkaTemplate<String, String> kafka, Environment env) {
        return new OutboxRelay(db, kafka, env.getProperty("senyap.outbox.batch-size", Integer.class, 100));
    }
}
