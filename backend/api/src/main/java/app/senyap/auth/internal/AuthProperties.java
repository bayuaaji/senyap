package app.senyap.auth.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code senyap.auth.*} (aturan M9).
 *
 * @param phonePepper     rahasia HMAC untuk {@code phone_hmac} dan hash IP. Dari env/KMS
 *                        ({@code SENYAP_AUTH_PHONE_PEPPER}), minimal 32 karakter. Tidak boleh berubah setelah
 *                        ada data: mengubahnya memutus semua akun dari nomor HP-nya.
 * @param signingJwk      private key ES256 (P-256) sebagai JWK JSON, dari env/KMS ({@code SENYAP_AUTH_SIGNING_JWK}).
 *                        Buat dengan {@code GenerateSigningKey}.
 * @param ephemeralSigningKey boleh membuat key acak saat start bila {@code signingJwk} kosong (hanya local/test;
 *                        semua access token jadi tidak valid setiap restart, refresh token tetap berlaku)
 */
@ConfigurationProperties("senyap.auth")
record AuthProperties(
        String phonePepper,
        String signingJwk,
        @DefaultValue("false") boolean ephemeralSigningKey,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("90d") Duration refreshTokenTtl,
        @DefaultValue Otp otp,
        @DefaultValue Email email) {

    /**
     * @param ttl              umur kode (spec: 5 menit)
     * @param maxAttempts      percobaan per kode (spec: 3)
     * @param perPhonePerHour  request OTP per nomor (spec §6: 3/jam)
     * @param perIpPerHour     request OTP per IP
     * @param verifyPerIpPerHour percobaan verify per IP (menahan tebak-kode lintas nomor)
     * @param retention        baris {@code otp_attempts} dihapus setelah ini
     */
    record Otp(
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("3") int perPhonePerHour,
            @DefaultValue("10") int perIpPerHour,
            @DefaultValue("30") int verifyPerIpPerHour,
            @DefaultValue("24h") Duration retention,
            @DefaultValue("000000") String mockCode) {
    }

    /**
     * Login email + kata sandi. Kode reset memakai {@link OtpCodes} yang sama (tetap {@code 000000} hanya di
     * profil local/test).
     *
     * @param resetTtl           umur kode reset (15 menit)
     * @param resetMaxAttempts   percobaan per kode reset
     * @param loginPerEmailPerHour / loginPerIpPerHour  percobaan login
     * @param forgotPerEmailPerHour / forgotPerIpPerHour permintaan kode reset
     * @param registerPerIpPerHour pendaftaran per IP
     * @param resetPerIpPerHour  percobaan reset per IP (menahan tebak-kode lintas email)
     * @param bcryptStrength     cost BCrypt
     */
    record Email(
            @DefaultValue("15m") Duration resetTtl,
            @DefaultValue("5") int resetMaxAttempts,
            @DefaultValue("10") int loginPerEmailPerHour,
            @DefaultValue("30") int loginPerIpPerHour,
            @DefaultValue("3") int forgotPerEmailPerHour,
            @DefaultValue("10") int forgotPerIpPerHour,
            @DefaultValue("10") int registerPerIpPerHour,
            @DefaultValue("30") int resetPerIpPerHour,
            @DefaultValue("10") int bcryptStrength) {
    }
}
