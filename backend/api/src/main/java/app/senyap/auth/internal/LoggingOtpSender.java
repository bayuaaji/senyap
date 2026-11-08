package app.senyap.auth.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock untuk local/test: tidak mengirim apa pun dan hanya mencatat kanalnya (tanpa nomor, tanpa kode). */
@Component
@Profile({"local", "test"})
class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String e164Phone, String code, Channel channel) {
        log.info("OTP mock via {} (tidak dikirim)", channel);
    }
}
