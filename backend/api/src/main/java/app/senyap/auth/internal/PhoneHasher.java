package app.senyap.auth.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * {@code phone_hmac = HMAC-SHA256(pepper, E.164)} (functional spec §2). Pepper hanya dikenal modul auth;
 * modul identity menerima hasil hash-nya saja. HMAC yang sama (dengan domain berbeda) dipakai untuk
 * alamat IP di rate limit dan {@code otp_attempts.ip_hash}, serta untuk hash kode OTP.
 */
@Component
class PhoneHasher {

    private final SecretKeySpec key;

    PhoneHasher(AuthProperties props) {
        String pepper = props.phonePepper();
        if (pepper == null || pepper.length() < 32) {
            throw new IllegalStateException("senyap.auth.phone-pepper wajib diisi (min. 32 karakter, dari env/KMS)");
        }
        this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    byte[] phone(String e164) {
        return hmac("phone:" + e164);
    }

    byte[] ip(String ip) {
        return hmac("ip:" + ip);
    }

    /** Hanya untuk kunci rate limit (kunci Redis tidak boleh berisi email mentah). */
    byte[] email(String normalizedEmail) {
        return hmac("email:" + normalizedEmail);
    }

    byte[] resetCode(long resetId, String code) {
        return hmac("reset:" + resetId + ":" + code);
    }

    byte[] otpCode(long attemptId, String code) {
        return hmac("otp:" + attemptId + ":" + code);
    }

    static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
