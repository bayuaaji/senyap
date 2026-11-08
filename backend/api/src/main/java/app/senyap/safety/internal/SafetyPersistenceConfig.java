package app.senyap.safety.internal;

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
 * Persistence modul safety: schema + user DB sendiri ({@code senyap.safety.datasource.*}), migrasi
 * {@code db/migration/safety}, transaction manager {@code safetyTx}, outbox {@code safety.outbox} → topik
 * {@code senyap.safety.topic}.
 */
@Configuration(proxyBeanMethods = false)
class SafetyPersistenceConfig {

    static final String MODULE = "safety";

    @Bean
    ModuleDatabase safetyDatabase(Environment env, DatabaseBootstrap bootstrap) {
        return ModuleInfrastructure.database(MODULE, env, bootstrap);
    }

    @Bean(destroyMethod = "")
    DataSource safetyDataSource(@Qualifier("safetyDatabase") ModuleDatabase db) {
        return db.dataSource();
    }

    @Bean
    PlatformTransactionManager safetyTx(@Qualifier("safetyDatabase") ModuleDatabase db) {
        return db.txManager();
    }

    @Bean
    OutboxWriter safetyOutbox(@Qualifier("safetyDatabase") ModuleDatabase db, Environment env, Tsid tsid, JsonMapper json) {
        return ModuleInfrastructure.outboxWriter(MODULE, db, env, tsid, json);
    }

    @Bean
    NewTopic safetyTopic(Environment env) {
        return ModuleInfrastructure.newTopic(MODULE, env);
    }

    @Bean
    @ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
    OutboxRelay safetyOutboxRelay(@Qualifier("safetyDatabase") ModuleDatabase db, KafkaTemplate<String, String> kafka,
                              Environment env) {
        return ModuleInfrastructure.outboxRelay(db, kafka, env);
    }
}
