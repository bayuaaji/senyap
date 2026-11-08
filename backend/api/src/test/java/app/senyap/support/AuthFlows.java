package app.senyap.support;

import java.util.concurrent.ThreadLocalRandom;

/** Data uji untuk alur login. Kode OTP di profil test selalu {@code 000000}. */
public final class AuthFlows {

    public static final String MOCK_CODE = "000000";

    private AuthFlows() {
    }

    /** Nomor seluler Indonesia acak (format lokal), supaya rate limit per nomor tidak saling ganggu antar test. */
    public static String randomLocalPhone() {
        return "0812" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    public static String toE164(String local) {
        return "+62" + local.substring(1);
    }

    public static String verifyBody(String phone, String code, String platform, String pushToken) {
        return """
                {"phone":"%s","code":"%s","device":{"platform":"%s","push_token":%s},"age_confirmed":true}"""
                .formatted(phone, code, platform, pushToken == null ? "null" : "\"" + pushToken + "\"");
    }
}
