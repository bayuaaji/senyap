package app.senyap.auth.internal;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Pembuat kode OTP 6 digit. Kode tetap ({@code senyap.auth.otp.mock-code}) hanya ada di profil local/test. */
@FunctionalInterface
interface OtpCodes {

    String next();

    @Configuration(proxyBeanMethods = false)
    class Config {

        @Bean
        @Profile({"local", "test"})
        OtpCodes fixedOtpCodes(AuthProperties props) {
            return () -> props.otp().mockCode();
        }

        @Bean
        @Profile("!local & !test")
        OtpCodes randomOtpCodes() {
            var random = new SecureRandom();
            return () -> String.format("%06d", random.nextInt(1_000_000));
        }
    }
}
