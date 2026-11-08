package app.senyap.auth.internal;

import app.senyap.contracts.auth.AccessTokens;
import app.senyap.identity.AccountSummary;
import app.senyap.identity.IdentityApi;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Access token JWT (15 menit, ES256) + refresh token opaque (90 hari) yang terikat ke satu device dan
 * dirotasi setiap dipakai (functional spec FS-01). Refresh token disimpan sebagai SHA-256; pemakaian
 * ulang token yang sudah dirotasi (di luar jeda {@link #REUSE_GRACE}) dianggap pencurian dan mencabut
 * seluruh family-nya.
 */
@Service
class TokenService {

    record Tokens(String accessToken, long expiresInSeconds, String refreshToken) {
    }

    /** Jaringan HP sering putus setelah server merotasi token tapi sebelum client menerima balasan. */
    static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    private enum RefreshOutcome { ROTATED, INVALID, REUSED }

    private record Rotation(RefreshOutcome outcome, long userId, long deviceId, String refreshToken) {
        static Rotation of(RefreshOutcome o) {
            return new Rotation(o, 0, 0, null);
        }
    }

    private record StoredToken(long id, long userId, long deviceId, UUID familyId, Instant expiresAt,
                               Instant revokedAt, Long replacedBy) {
    }

    private final ModuleDatabase db;
    private final SigningKeys keys;
    private final IdentityApi identity;
    private final Tsid tsid;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    TokenService(@Qualifier("authDatabase") ModuleDatabase db, SigningKeys keys, IdentityApi identity, Tsid tsid,
                 AuthProperties props) {
        this.db = db;
        this.keys = keys;
        this.identity = identity;
        this.tsid = tsid;
        this.props = props;
    }

    /** Login sukses: device baru + family refresh token baru. */
    Tokens createSession(long userId, UUID publicId, String platform, String pushToken) {
        long deviceId = tsid.next();
        String refresh = db.tx().execute(s -> {
            if (pushToken != null) {
                releasePushToken(pushToken);
            }
            db.jdbc().sql("""
                            INSERT INTO devices (id, user_id, platform, push_token, last_seen_at)
                            VALUES (:id, :user, :platform, :push, now())""")
                    .param("id", deviceId).param("user", userId).param("platform", platform).param("push", pushToken)
                    .update();
            return insertRefreshToken(userId, deviceId, UUID.randomUUID()).token();
        });
        return new Tokens(accessToken(publicId, deviceId), props.accessTokenTtl().toSeconds(), refresh);
    }

    Tokens refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank() || rawRefreshToken.length() > 200) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        Rotation r = db.tx().execute(s -> rotate(sha256(rawRefreshToken)));
        if (r.outcome() != RefreshOutcome.ROTATED) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        AccountSummary account = identity.findAccounts(List.of(r.userId())).get(r.userId());
        if (account == null || !account.status().canUseSession()) {
            revokeAll(r.userId());
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return new Tokens(accessToken(account.publicId(), r.deviceId()), props.accessTokenTtl().toSeconds(),
                r.refreshToken());
    }

    private Rotation rotate(byte[] hash) {
        var found = db.jdbc().sql("""
                        SELECT id, user_id, device_id, family_id, expires_at, revoked_at, replaced_by
                        FROM refresh_tokens WHERE token_hash = :h FOR UPDATE""")
                .param("h", hash)
                .query((rs, n) -> new StoredToken(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("device_id"),
                        rs.getObject("family_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                        (Long) rs.getObject("replaced_by")))
                .optional();
        if (found.isEmpty()) {
            return Rotation.of(RefreshOutcome.INVALID);
        }
        var t = found.get();
        var now = Instant.now();
        if (t.revokedAt() != null) {
            boolean retryAfterLostResponse = t.replacedBy() != null && now.isBefore(t.revokedAt().plus(REUSE_GRACE))
                    && revokeIfUnused(t.replacedBy());
            if (!retryAfterLostResponse) {
                db.jdbc().sql("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL")
                        .param("f", t.familyId()).update();
                return Rotation.of(RefreshOutcome.REUSED);
            }
        } else if (!now.isBefore(t.expiresAt())) {
            return Rotation.of(RefreshOutcome.INVALID);
        }
        var next = insertRefreshToken(t.userId(), t.deviceId(), t.familyId());
        if (t.revokedAt() == null) {
            db.jdbc().sql("UPDATE refresh_tokens SET revoked_at = now(), replaced_by = :next WHERE id = :id")
                    .param("next", next.id()).param("id", t.id()).update();
        } else {
            // Retry dalam jeda: penggantinya yang belum terpakai sudah dicabut, rantai diarahkan ke token baru.
            db.jdbc().sql("UPDATE refresh_tokens SET replaced_by = :next WHERE id = :id")
                    .param("next", next.id()).param("id", t.id()).update();
        }
        db.jdbc().sql("UPDATE devices SET last_seen_at = now() WHERE id = :d").param("d", t.deviceId()).update();
        return new Rotation(RefreshOutcome.ROTATED, t.userId(), t.deviceId(), next.token());
    }

    /** Mencabut token pengganti bila belum pernah dipakai. Lepas {@code replaced_by} supaya unik tetap terjaga. */
    private boolean revokeIfUnused(long replacementId) {
        return db.jdbc().sql("""
                        UPDATE refresh_tokens SET revoked_at = now()
                        WHERE id = :id AND revoked_at IS NULL AND replaced_by IS NULL""")
                .param("id", replacementId).update() == 1;
    }

    /** Logout satu device: device + semua refresh token-nya dihapus. */
    void logout(long userId, long deviceId) {
        db.tx().executeWithoutResult(s -> db.jdbc().sql("DELETE FROM devices WHERE id = :d AND user_id = :u")
                .param("d", deviceId).param("u", userId).update());
    }

    /** Hapus akun / ban: semua device + refresh token user dihapus. Access token habis sendiri ≤ 15 menit. */
    void revokeAll(long userId) {
        db.tx().executeWithoutResult(s -> db.jdbc().sql("DELETE FROM devices WHERE user_id = :u")
                .param("u", userId).update());
    }

    /** @return false bila device sudah tidak ada (logout di tempat lain) */
    boolean updatePushToken(long userId, long deviceId, String pushToken) {
        Integer n = db.tx().execute(s -> {
            if (pushToken != null) {
                releasePushToken(pushToken);
            }
            return db.jdbc().sql("UPDATE devices SET push_token = :t, last_seen_at = now() WHERE id = :d AND user_id = :u")
                    .param("t", pushToken).param("d", deviceId).param("u", userId).update();
        });
        return n != null && n == 1;
    }

    /** Satu push token hanya milik satu device (aplikasi dipasang ulang / ganti akun di HP yang sama). */
    private void releasePushToken(String pushToken) {
        db.jdbc().sql("UPDATE devices SET push_token = NULL WHERE push_token = :t").param("t", pushToken).update();
    }

    private record IssuedRefresh(long id, String token) {
    }

    private IssuedRefresh insertRefreshToken(long userId, long deviceId, UUID familyId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long id = tsid.next();
        db.jdbc().sql("""
                        INSERT INTO refresh_tokens (id, user_id, device_id, token_hash, family_id, expires_at)
                        VALUES (:id, :user, :device, :hash, :family, :exp)""")
                .param("id", id).param("user", userId).param("device", deviceId)
                .param("hash", sha256(token)).param("family", familyId)
                .param("exp", Timestamp.from(Instant.now().plus(props.refreshTokenTtl())))
                .update();
        return new IssuedRefresh(id, token);
    }

    private String accessToken(UUID publicId, long deviceId) {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(AccessTokens.ISSUER)
                .audience(AccessTokens.AUDIENCE)
                .subject(publicId.toString())
                .claim(AccessTokens.CLAIM_DEVICE, Long.toString(deviceId))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(props.accessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .build();
        var key = keys.signingKey();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
