package app.senyap.media.internal;

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
 * Persistence modul media: schema + user DB sendiri ({@code senyap.media.datasource.*}), migrasi
 * {@code db/migration/media}, transaction manager {@code mediaTx}, outbox {@code media.outbox} → topik
 * {@code senyap.media.topic}.
 */
@Configuration(proxyBeanMethods = false)
class MediaPersistenceConfig {

    static final String MODULE = "media";

    @Bean
    ModuleDatabase mediaDatabase(Environment env, DatabaseBootstrap bootstrap) {
        return ModuleInfrastructure.database(MODULE, env, bootstrap);
    }

    @Bean(destroyMethod = "")
    DataSource mediaDataSource(@Qualifier("mediaDatabase") ModuleDatabase db) {
        return db.dataSource();
    }

    @Bean
    PlatformTransactionManager mediaTx(@Qualifier("mediaDatabase") ModuleDatabase db) {
        return db.txManager();
    }

    @Bean
    OutboxWriter mediaOutbox(@Qualifier("mediaDatabase") ModuleDatabase db, Environment env, Tsid tsid, JsonMapper json) {
        return ModuleInfrastructure.outboxWriter(MODULE, db, env, tsid, json);
    }

    @Bean
    NewTopic mediaTopic(Environment env) {
        return ModuleInfrastructure.newTopic(MODULE, env);
    }

    @Bean
    @ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
    OutboxRelay mediaOutboxRelay(@Qualifier("mediaDatabase") ModuleDatabase db, KafkaTemplate<String, String> kafka,
                              Environment env) {
        return ModuleInfrastructure.outboxRelay(db, kafka, env);
    }
}
