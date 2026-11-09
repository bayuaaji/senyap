package app.senyap.auth.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock untuk local/test: tidak mengirim apa pun dan hanya mencatat bahwa email dikirim (tanpa alamat, tanpa kode). */
@Component
@Profile({"local", "test"})
class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void sendPasswordResetCode(String email, String code, long validMinutes) {
        log.info("Email reset kata sandi mock (tidak dikirim)");
    }
}
