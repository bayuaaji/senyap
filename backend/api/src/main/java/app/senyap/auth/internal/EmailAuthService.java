package app.senyap.auth.internal;

import app.senyap.identity.IdentityApi;
import app.senyap.identity.SignInResult;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.ratelimit.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Daftar/masuk dengan email + kata sandi (functional spec FS-01, revisi 9 Nov 2026), tambahan di samping OTP.
 * <ul>
 *   <li>Email dinormalisasi (trim + huruf kecil) dan disimpan plaintext di {@code email_credentials} karena
 *       dibutuhkan untuk reset kata sandi. Tidak pernah ada di respons, log, event, metrik, atau kunci Redis
 *       (rate limit memakai HMAC email).</li>
 *   <li>Kata sandi: BCrypt, minimal 8 karakter, maksimal 72 byte UTF-8 (batas BCrypt).</li>
 *   <li>Login gagal selalu {@code INVALID_CREDENTIALS} tanpa membedakan email tidak terdaftar / kata sandi salah;
 *       email tak terdaftar tetap menjalankan BCrypt pada hash dummy supaya waktu respons serupa.</li>
 *   <li>Lupa kata sandi selalu 202; kode reset 6 digit berlaku 15 menit, hanya kode terakhir yang berlaku, reset
 *       sukses mencabut semua sesi user.</li>
 * </ul>
 */
@Service
class EmailAuthService {

    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MAX_EMAIL = 254;

    /** Cukup untuk menolak salah ketik; validasi sebenarnya = email reset sampai ke pemiliknya. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private enum ResetOutcome { OK, WRONG_CODE, NO_VALID_CODE }

    private record Credential(long userId, String passwordHash) {
    }

    private record ResetCode(long id, byte[] codeHash, int attempts, Instant expiresAt, boolean used) {
    }

    private final ModuleDatabase db;
    private final IdentityApi identity;
    private final TokenService tokens;
    private final PhoneHasher hasher;
    private final OtpCodes codes;
    private final EmailSender sender;
    private final RateLimiter rateLimiter;
    private final Tsid tsid;
    private final AuthProperties.Email props;
    private final BCryptPasswordEncoder encoder;
    private final String dummyHash;

    EmailAuthService(@Qualifier("authDatabase") ModuleDatabase db, IdentityApi identity, TokenService tokens,
                     PhoneHasher hasher, OtpCodes codes, ObjectProvider<EmailSender> senders, RateLimiter rateLimiter,
                     Tsid tsid, AuthProperties props, Environment env) {
        this.db = db;
        this.identity = identity;
        this.tokens = tokens;
        this.hasher = hasher;
        this.codes = codes;
        this.rateLimiter = rateLimiter;
        this.tsid = tsid;
        this.props = props.email();
        EmailSender sender = senders.getIfAvailable();
        if (sender == null) {
            // acceptsProfiles juga memperhitungkan profil default (getActiveProfiles() kosong tanpa profil eksplisit).
            if (env.acceptsProfiles(Profiles.of("local | test"))) {
                throw new IllegalStateException("LoggingEmailSender seharusnya aktif di profil local/test");
            }
            throw new IllegalStateException("Implementasi EmailSender wajib ada di luar profil local/test "
                    + "(kode reset kata sandi harus benar-benar terkirim). Lihat PROGRESS.md.");
        }
        this.sender = sender;
        this.encoder = new BCryptPasswordEncoder(this.props.bcryptStrength());
        this.dummyHash = encoder.encode("senyap-dummy-password-untuk-waktu-konstan");
    }

    /** @return akun baru (status ACTIVE, belum onboarding) */
    SignInResult register(String rawEmail, String password, String clientIp) {
        rateLimiter.hit("auth:email-register:ip:" + hex(hasher.ip(clientIp)), props.registerPerIpPerHour(),
                Duration.ofHours(1));
        String email = normalize(rawEmail).orElseThrow(() -> new ApiException(ErrorCode.INVALID_EMAIL));
        checkPassword(password);
        if (find(email).isPresent()) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        String hash = encoder.encode(password);
        SignInResult account = identity.createAccount();
        try {
            db.tx().executeWithoutResult(s -> db.jdbc().sql("""
                            INSERT INTO email_credentials (user_id, email, password_hash) VALUES (:u, :e, :h)""")
                    .param("u", account.userId()).param("e", email).param("h", hash).update());
        } catch (DuplicateKeyException e) {
            // Email yang sama didaftarkan paralel: akun yang baru dibuat tidak punya kredensial, jadi dihapus
            // (ikut di-purge job identity). Satu transaksi = satu schema, jadi tidak bisa di-rollback bersama.
            identity.markDeleted(account.userId());
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        return account;
    }

    SignInResult login(String rawEmail, String password, String clientIp) {
        rateLimiter.hit("auth:email-login:ip:" + hex(hasher.ip(clientIp)), props.loginPerIpPerHour(), Duration.ofHours(1));
        Optional<String> email = normalize(rawEmail);
        if (email.isPresent()) {
            rateLimiter.hit("auth:email-login:email:" + hex(hasher.email(email.get())), props.loginPerEmailPerHour(),
                    Duration.ofHours(1));
        }
        Optional<Credential> credential = email.flatMap(this::find);
        String candidate = password == null ? "" : password;
        boolean match = encoder.matches(candidate, credential.map(Credential::passwordHash).orElse(dummyHash));
        if (credential.isEmpty() || !match) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        long userId = credential.get().userId();
        Optional<SignInResult> account = identity.signInAccount(userId);
        if (account.isEmpty()) {
            // Akun sudah di-purge tapi consumer user.purged belum sempat menghapus kredensialnya.
            forget(userId);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return account.get();
    }

    /** Selalu sukses dari sisi client (202), terdaftar atau tidak. */
    void forgot(String rawEmail, String clientIp) {
        rateLimiter.hit("auth:email-forgot:ip:" + hex(hasher.ip(clientIp)), props.forgotPerIpPerHour(), Duration.ofHours(1));
        Optional<String> email = normalize(rawEmail);
        if (email.isEmpty()) {
            return;
        }
        rateLimiter.hit("auth:email-forgot:email:" + hex(hasher.email(email.get())), props.forgotPerEmailPerHour(),
                Duration.ofHours(1));
        Optional<Credential> credential = find(email.get());
        if (credential.isEmpty()) {
            return;
        }
        long id = tsid.next();
        String code = codes.next();
        var now = Instant.now();
        db.tx().executeWithoutResult(s -> db.jdbc().sql("""
                        INSERT INTO password_reset_codes (id, user_id, code_hash, expires_at, created_at)
                        VALUES (:id, :u, :code, :exp, :now)""")
                .param("id", id).param("u", credential.get().userId()).param("code", hasher.resetCode(id, code))
                .param("exp", Timestamp.from(now.plus(props.resetTtl()))).param("now", Timestamp.from(now))
                .update());
        sender.sendPasswordResetCode(email.get(), code, props.resetTtl().toMinutes());
    }

    /** Kode benar → kata sandi diganti dan semua sesi user dicabut. */
    void reset(String rawEmail, String code, String newPassword, String clientIp) {
        rateLimiter.hit("auth:email-reset:ip:" + hex(hasher.ip(clientIp)), props.resetPerIpPerHour(), Duration.ofHours(1));
        checkPassword(newPassword);
        Optional<Credential> credential = normalize(rawEmail).flatMap(this::find);
        if (credential.isEmpty() || code == null || !code.matches("[0-9]{6}")) {
            throw new ApiException(ErrorCode.OTP_INVALID, "Kode salah atau sudah tidak berlaku.");
        }
        long userId = credential.get().userId();
        String hash = encoder.encode(newPassword);
        // Percobaan yang gagal tetap di-commit, jadi error dilempar setelah transaksi selesai.
        ResetOutcome outcome = db.tx().execute(s -> {
            var latest = db.jdbc().sql("""
                            SELECT id, code_hash, attempts, expires_at, used_at IS NOT NULL AS used
                            FROM password_reset_codes WHERE user_id = :u
                            ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE""")
                    .param("u", userId)
                    .query((rs, n) -> new ResetCode(rs.getLong("id"), rs.getBytes("code_hash"), rs.getInt("attempts"),
                            rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("used")))
                    .optional();
            if (latest.isEmpty()) {
                return ResetOutcome.NO_VALID_CODE;
            }
            var c = latest.get();
            if (c.used() || c.attempts() >= props.resetMaxAttempts() || !Instant.now().isBefore(c.expiresAt())) {
                return ResetOutcome.NO_VALID_CODE;
            }
            boolean match = PhoneHasher.constantTimeEquals(c.codeHash(), hasher.resetCode(c.id(), code));
            db.jdbc().sql("""
                            UPDATE password_reset_codes SET attempts = attempts + 1,
                                   used_at = CASE WHEN :match THEN now() END
                            WHERE id = :id""")
                    .param("match", match).param("id", c.id()).update();
            if (match) {
                db.jdbc().sql("""
                                UPDATE email_credentials SET password_hash = :h, password_changed_at = now()
                                WHERE user_id = :u""")
                        .param("h", hash).param("u", userId).update();
            }
            return match ? ResetOutcome.OK : ResetOutcome.WRONG_CODE;
        });
        switch (outcome) {
            case OK -> tokens.revokeAll(userId);
            case WRONG_CODE -> throw new ApiException(ErrorCode.OTP_INVALID, "Kode salah.");
            case NO_VALID_CODE -> throw new ApiException(ErrorCode.OTP_INVALID, "Kode sudah tidak berlaku. Minta kode baru.");
        }
    }

    /** Akun di-purge: email + kode reset dihapus (email tidak lagi terhubung, dan bisa mendaftar akun baru). */
    void forget(long userId) {
        db.tx().executeWithoutResult(s -> db.jdbc().sql("DELETE FROM email_credentials WHERE user_id = :u")
                .param("u", userId).update());
    }

    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            return Optional.empty();
        }
        return Optional.of(email);
    }

    static void checkPassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD
                || password.isBlank()) {
            throw new ApiException(ErrorCode.WEAK_PASSWORD);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ApiException(ErrorCode.WEAK_PASSWORD, "Kata sandi terlalu panjang (maksimal 72 karakter).");
        }
    }

    private Optional<Credential> find(String email) {
        return db.jdbc().sql("SELECT user_id, password_hash FROM email_credentials WHERE email = :e")
                .param("e", email)
                .query((rs, n) -> new Credential(rs.getLong(1), rs.getString(2)))
                .optional();
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
