package app.senyap.auth.internal;

import app.senyap.shared.db.ModuleDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker: hapus {@code otp_attempts} + kode reset kata sandi lama dan refresh token yang sudah kedaluwarsa. */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class AuthMaintenanceJob {

    private final ModuleDatabase db;
    private final AuthProperties props;

    AuthMaintenanceJob(@Qualifier("authDatabase") ModuleDatabase db, AuthProperties props) {
        this.db = db;
        this.props = props;
    }

    @Scheduled(initialDelayString = "${senyap.auth.maintenance-interval:1h}",
            fixedDelayString = "${senyap.auth.maintenance-interval:1h}")
    void cleanUp() {
        var otpCutoff = Timestamp.from(Instant.now().minus(props.otp().retention()));
        db.tx().executeWithoutResult(s -> {
            db.jdbc().sql("DELETE FROM otp_attempts WHERE created_at < :c").param("c", otpCutoff).update();
            db.jdbc().sql("DELETE FROM refresh_tokens WHERE expires_at < now() - interval '1 day'").update();
            db.jdbc().sql("DELETE FROM password_reset_codes WHERE created_at < :c").param("c", otpCutoff).update();
        });
    }
}
