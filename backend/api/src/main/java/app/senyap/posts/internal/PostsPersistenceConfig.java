package app.senyap.posts.internal;

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
 * Persistence modul posts: schema + user DB sendiri ({@code senyap.posts.datasource.*}), migrasi
 * {@code db/migration/posts}, transaction manager {@code postsTx}, outbox {@code posts.outbox} → topik
 * {@code senyap.posts.topic}.
 */
@Configuration(proxyBeanMethods = false)
class PostsPersistenceConfig {

    static final String MODULE = "posts";

    @Bean
    ModuleDatabase postsDatabase(Environment env, DatabaseBootstrap bootstrap) {
        return ModuleInfrastructure.database(MODULE, env, bootstrap);
    }

    @Bean(destroyMethod = "")
    DataSource postsDataSource(@Qualifier("postsDatabase") ModuleDatabase db) {
        return db.dataSource();
    }

    @Bean
    PlatformTransactionManager postsTx(@Qualifier("postsDatabase") ModuleDatabase db) {
        return db.txManager();
    }

    @Bean
    OutboxWriter postsOutbox(@Qualifier("postsDatabase") ModuleDatabase db, Environment env, Tsid tsid, JsonMapper json) {
        return ModuleInfrastructure.outboxWriter(MODULE, db, env, tsid, json);
    }

    @Bean
    NewTopic postsTopic(Environment env) {
        return ModuleInfrastructure.newTopic(MODULE, env);
    }

    @Bean
    @ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
    OutboxRelay postsOutboxRelay(@Qualifier("postsDatabase") ModuleDatabase db, KafkaTemplate<String, String> kafka,
                              Environment env) {
        return ModuleInfrastructure.outboxRelay(db, kafka, env);
    }
}
