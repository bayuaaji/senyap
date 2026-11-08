package app.senyap.identity.internal;

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
 * Persistence modul identity: schema + user DB sendiri ({@code senyap.identity.datasource.*}), migrasi
 * {@code db/migration/identity}, transaction manager {@code identityTx}, outbox {@code identity.outbox} → topik
 * {@code senyap.identity.topic}.
 */
@Configuration(proxyBeanMethods = false)
class IdentityPersistenceConfig {

    static final String MODULE = "identity";

    @Bean
    ModuleDatabase identityDatabase(Environment env, DatabaseBootstrap bootstrap) {
        return ModuleInfrastructure.database(MODULE, env, bootstrap);
    }

    @Bean(destroyMethod = "")
    DataSource identityDataSource(@Qualifier("identityDatabase") ModuleDatabase db) {
        return db.dataSource();
    }

    @Bean
    PlatformTransactionManager identityTx(@Qualifier("identityDatabase") ModuleDatabase db) {
        return db.txManager();
    }

    @Bean
    OutboxWriter identityOutbox(@Qualifier("identityDatabase") ModuleDatabase db, Environment env, Tsid tsid, JsonMapper json) {
        return ModuleInfrastructure.outboxWriter(MODULE, db, env, tsid, json);
    }

    @Bean
    NewTopic identityTopic(Environment env) {
        return ModuleInfrastructure.newTopic(MODULE, env);
    }

    @Bean
    @ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
    OutboxRelay identityOutboxRelay(@Qualifier("identityDatabase") ModuleDatabase db, KafkaTemplate<String, String> kafka,
                              Environment env) {
        return ModuleInfrastructure.outboxRelay(db, kafka, env);
    }
}
