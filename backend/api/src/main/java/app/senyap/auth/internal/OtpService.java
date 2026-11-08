package app.senyap.auth.internal;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.ratelimit.RateLimiter;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * OTP (functional spec FS-01): 6 digit, berlaku 5 menit, maksimal 3 percobaan per kode; hanya kode
 * terakhir per nomor yang berlaku. Rate limit per nomor dan per IP (§6). Yang disimpan hanya
 * {@code phone_hmac}, hash kode, dan hash IP; nomor HP dan kode tidak pernah ke DB atau log.
 */
@Service
class OtpService {

    private enum Outcome { OK, WRONG_CODE, NO_VALID_CODE }

    private record Attempt(long id, byte[] codeHash, int attempts, Instant expiresAt, boolean verified) {
    }

    private final ModuleDatabase db;
    private final PhoneHasher hasher;
    private final OtpSender sender;
    private final OtpCodes codes;
    private final RateLimiter rateLimiter;
    private final Tsid tsid;
    private final AuthProperties.Otp props;

    OtpService(@Qualifier("authDatabase") ModuleDatabase db, PhoneHasher hasher, OtpSender sender, OtpCodes codes,
               RateLimiter rateLimiter, Tsid tsid, AuthProperties props) {
        this.db = db;
        this.hasher = hasher;
        this.sender = sender;
        this.codes = codes;
        this.rateLimiter = rateLimiter;
        this.tsid = tsid;
        this.props = props.otp();
    }

    void request(String rawPhone, OtpSender.Channel channel, String clientIp) {
        String phone = PhoneNumbers.normalize(rawPhone);
        byte[] phoneHmac = hasher.phone(phone);
        byte[] ipHash = hasher.ip(clientIp);
        rateLimiter.hit("auth:otp:ip:" + hex(ipHash), props.perIpPerHour(), Duration.ofHours(1));
        rateLimiter.hit("auth:otp:phone:" + hex(phoneHmac), props.perPhonePerHour(), Duration.ofHours(1));

        long id = tsid.next();
        String code = codes.next();
        var now = Instant.now();
        db.tx().executeWithoutResult(s -> db.jdbc().sql("""
                        INSERT INTO otp_attempts (id, phone_hmac, channel, code_hash, ip_hash, expires_at, created_at)
                        VALUES (:id, :phone, :channel, :code, :ip, :exp, :now)""")
                .param("id", id).param("phone", phoneHmac).param("channel", channel.name())
                .param("code", hasher.otpCode(id, code)).param("ip", ipHash)
                .param("exp", Timestamp.from(now.plus(props.ttl()))).param("now", Timestamp.from(now))
                .update());
        sender.send(phone, code, channel);
    }

    /** @return {@code phone_hmac} nomor yang terverifikasi */
    byte[] verify(String rawPhone, String code, String clientIp) {
        String phone = PhoneNumbers.normalize(rawPhone);
        byte[] phoneHmac = hasher.phone(phone);
        rateLimiter.hit("auth:otp-verify:ip:" + hex(hasher.ip(clientIp)), props.verifyPerIpPerHour(), Duration.ofHours(1));
        if (code == null || !code.matches("[0-9]{6}")) {
            throw new ApiException(ErrorCode.OTP_INVALID, "Kode salah.");
        }
        // Percobaan yang gagal tetap di-commit, jadi error dilempar setelah transaksi selesai.
        Outcome outcome = db.tx().execute(s -> {
            var latest = db.jdbc().sql("""
                            SELECT id, code_hash, attempts, expires_at, verified_at IS NOT NULL AS verified
                            FROM otp_attempts WHERE phone_hmac = :phone
                            ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE""")
                    .param("phone", phoneHmac)
                    .query((rs, n) -> new Attempt(rs.getLong("id"), rs.getBytes("code_hash"), rs.getInt("attempts"),
                            rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("verified")))
                    .optional();
            if (latest.isEmpty()) {
                return Outcome.NO_VALID_CODE;
            }
            var a = latest.get();
            if (a.verified() || a.attempts() >= props.maxAttempts() || !Instant.now().isBefore(a.expiresAt())) {
                return Outcome.NO_VALID_CODE;
            }
            boolean match = PhoneHasher.constantTimeEquals(a.codeHash(), hasher.otpCode(a.id(), code));
            db.jdbc().sql("""
                            UPDATE otp_attempts SET attempts = attempts + 1,
                                   verified_at = CASE WHEN :match THEN now() END
                            WHERE id = :id""")
                    .param("match", match).param("id", a.id())
                    .update();
            return match ? Outcome.OK : Outcome.WRONG_CODE;
        });
        return switch (outcome) {
            case OK -> phoneHmac;
            case WRONG_CODE -> throw new ApiException(ErrorCode.OTP_INVALID, "Kode salah.");
            case NO_VALID_CODE -> throw new ApiException(ErrorCode.OTP_INVALID, "Kode sudah tidak berlaku. Minta kode baru.");
        };
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
