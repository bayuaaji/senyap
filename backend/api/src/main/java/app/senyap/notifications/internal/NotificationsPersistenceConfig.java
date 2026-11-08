package app.senyap.notifications.internal;

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
 * Persistence modul notifications: schema + user DB sendiri ({@code senyap.notifications.datasource.*}), migrasi
 * {@code db/migration/notifications}, transaction manager {@code notificationsTx}, outbox {@code notifications.outbox} → topik
 * {@code senyap.notifications.topic}.
 */
@Configuration(proxyBeanMethods = false)
class NotificationsPersistenceConfig {

    static final String MODULE = "notifications";

    @Bean
    ModuleDatabase notificationsDatabase(Environment env, DatabaseBootstrap bootstrap) {
        return ModuleInfrastructure.database(MODULE, env, bootstrap);
    }

    @Bean(destroyMethod = "")
    DataSource notificationsDataSource(@Qualifier("notificationsDatabase") ModuleDatabase db) {
        return db.dataSource();
    }

    @Bean
    PlatformTransactionManager notificationsTx(@Qualifier("notificationsDatabase") ModuleDatabase db) {
        return db.txManager();
    }

    @Bean
    OutboxWriter notificationsOutbox(@Qualifier("notificationsDatabase") ModuleDatabase db, Environment env, Tsid tsid, JsonMapper json) {
        return ModuleInfrastructure.outboxWriter(MODULE, db, env, tsid, json);
    }

    @Bean
    NewTopic notificationsTopic(Environment env) {
        return ModuleInfrastructure.newTopic(MODULE, env);
    }

    @Bean
    @ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
    OutboxRelay notificationsOutboxRelay(@Qualifier("notificationsDatabase") ModuleDatabase db, KafkaTemplate<String, String> kafka,
                              Environment env) {
        return ModuleInfrastructure.outboxRelay(db, kafka, env);
    }
}
