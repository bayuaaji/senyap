package app.senyap.chat.internal;

import app.senyap.shared.db.DatabaseBootstrap;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.outbox.ModuleInfrastructure;
import app.senyap.shared.outbox.OutboxRelay;
import app.senyap.shared.outbox.OutboxWriter;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persistence modul chat: schema + user DB sendiri ({@code senyap.chat.datasource.*}), migrasi
 * {@code db/migration/chat}, transaction manager {@code chatTx}, outbox {@code chat.outbox} → topik
 * {@code senyap.chat.topic}.
 */
@Configuration(proxyBeanMethods = false)
class ChatPersistenceConfig {

    static final String MODULE = "chat";

    @Bean
    ModuleDatabase chatDatabase(Environment env, DatabaseBootstrap bootstrap) {
        return ModuleInfrastructure.database(MODULE, env, bootstrap);
    }

    @Bean(destroyMethod = "")
    DataSource chatDataSource(@Qualifier("chatDatabase") ModuleDatabase db) {
        return db.dataSource();
    }

    @Bean
    PlatformTransactionManager chatTx(@Qualifier("chatDatabase") ModuleDatabase db) {
        return db.txManager();
    }

    @Bean
    OutboxWriter chatOutbox(@Qualifier("chatDatabase") ModuleDatabase db, Environment env, Tsid tsid, JsonMapper json) {
        return ModuleInfrastructure.outboxWriter(MODULE, db, env, tsid, json);
    }

    @Bean
    NewTopic chatTopic(Environment env) {
        return ModuleInfrastructure.newTopic(MODULE, env);
    }

    /**
     * Relay chat sengaja bukan bean {@link OutboxRelay} (scheduler bersama polling 500 ms): dijalankan
     * {@link ChatOutboxPump} supaya pesan DM cepat sampai ke delivery router.
     */
    @Bean
    @ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
    ChatOutboxPump chatOutboxPump(@Qualifier("chatDatabase") ModuleDatabase db, KafkaTemplate<String, String> kafka,
                                  Environment env) {
        OutboxRelay relay = ModuleInfrastructure.outboxRelay(db, kafka, env);
        return new ChatOutboxPump(relay,
                env.getProperty("senyap.chat.outbox.poll-interval", java.time.Duration.class, java.time.Duration.ofMillis(100)));
    }
}
