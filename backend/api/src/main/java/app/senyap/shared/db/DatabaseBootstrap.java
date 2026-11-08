package app.senyap.shared.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Membuat role {@code senyap_<modul>} dan schema {@code <modul>} milik role itu (aturan M1) bila belum ada.
 * Role tidak diberi akses ke schema lain, jadi query lintas schema gagal dengan "permission denied".
 */
@Component
public class DatabaseBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,40}");
    private static final Pattern SAFE_PASSWORD = Pattern.compile("[A-Za-z0-9_.-]{8,128}");

    private final DatabaseBootstrapProperties props;

    public DatabaseBootstrap(DatabaseBootstrapProperties props) {
        this.props = props;
    }

    public synchronized void ensureModule(String schema, ModuleDatabaseProperties db) {
        if (!props.enabled()) {
            return;
        }
        if (!IDENT.matcher(schema).matches() || !IDENT.matcher(db.username()).matches()) {
            throw new IllegalArgumentException("Nama schema/role tidak valid: " + schema);
        }
        if (!SAFE_PASSWORD.matcher(db.password()).matches()) {
            throw new IllegalArgumentException("Password DB modul " + schema + " harus 8–128 karakter [A-Za-z0-9_.-]");
        }
        try (Connection c = DriverManager.getConnection(props.url(), props.username(), props.password());
             Statement st = c.createStatement()) {
            // api dan worker bisa start bersamaan; DDL role/schema diserialkan lewat advisory lock
            // (dilepas otomatis saat koneksi ditutup).
            st.execute("SELECT pg_advisory_lock(hashtext('senyap.db.bootstrap'))");
            st.execute("""
                    DO $$ BEGIN
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                        CREATE ROLE %1$s LOGIN PASSWORD '%2$s';
                      ELSE
                        ALTER ROLE %1$s LOGIN PASSWORD '%2$s';
                      END IF;
                    END $$""".formatted(db.username(), db.password()));
            st.execute("CREATE SCHEMA IF NOT EXISTS %s AUTHORIZATION %s".formatted(schema, db.username()));
            st.execute("ALTER ROLE %s SET search_path = %s".formatted(db.username(), schema));
            st.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            log.info("Bootstrap DB: role {} + schema {} siap", db.username(), schema);
        } catch (SQLException e) {
            throw new IllegalStateException("Gagal bootstrap schema " + schema, e);
        }
    }
}
