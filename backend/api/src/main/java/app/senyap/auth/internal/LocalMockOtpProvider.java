package app.senyap.auth.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock OTP untuk profil {@code local}/{@code test}: tidak mengirim apa pun, kode selalu {@code 000000}. */
@Component
@Profile({"local", "test"})
class LocalMockOtpProvider implements OtpProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalMockOtpProvider.class);

    private final String code;

    LocalMockOtpProvider(@Value("${senyap.auth.otp.mock-code:000000}") String code) {
        this.code = code;
    }

    @Override
    public void send(String e164Phone, Channel channel) {
        log.info("OTP mock via {} (kode lokal tetap)", channel);
    }

    @Override
    public boolean verify(String e164Phone, String submitted) {
        return code.equals(submitted);
    }
}
