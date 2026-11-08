package app.senyap.shared.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Semua infrastruktur DB satu modul: pool koneksi dengan user modul itu, migrasi Flyway dari
 * {@code classpath:db/migration/<modul>}, {@link JdbcClient}, dan transaction manager sendiri.
 *
 * <p>Karena tiap modul punya transaction manager sendiri, satu transaksi secara struktural hanya bisa
 * menyentuh satu schema (aturan M6). Gunakan {@code @Transactional("<modul>Tx")} atau {@link #tx()}.
 */
public final class ModuleDatabase implements DisposableBean {

    private final String schema;
    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;
    private final DataSourceTransactionManager txManager;
    private final TransactionTemplate tx;

    private ModuleDatabase(String schema, HikariDataSource dataSource) {
        this.schema = schema;
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.txManager = new DataSourceTransactionManager(dataSource);
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Membaca {@code senyap.<schema>.datasource.*}, menjalankan bootstrap (lokal), lalu migrasi Flyway modul.
     */
    public static ModuleDatabase create(String schema, Environment env, DatabaseBootstrap bootstrap) {
        var props = Binder.get(env)
                .bind("senyap." + schema + ".datasource", ModuleDatabaseProperties.class)
                .orElseThrow(() -> new IllegalStateException("senyap." + schema + ".datasource belum dikonfigurasi"));
        bootstrap.ensureModule(schema, props);

        var cfg = new HikariConfig();
        cfg.setPoolName("db-" + schema);
        cfg.setJdbcUrl(props.url());
        cfg.setUsername(props.username());
        cfg.setPassword(props.password());
        cfg.setMaximumPoolSize(props.poolSizeOrDefault());
        cfg.setMinimumIdle(1);
        cfg.setSchema(schema);
        var ds = new HikariDataSource(cfg);

        Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .locations("classpath:db/migration/" + schema)
                .load()
                .migrate();
        return new ModuleDatabase(schema, ds);
    }

    public String schema() {
        return schema;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public PlatformTransactionManager txManager() {
        return txManager;
    }

    public TransactionTemplate tx() {
        return tx;
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
