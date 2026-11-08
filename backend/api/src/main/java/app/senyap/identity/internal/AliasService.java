package app.senyap.identity.internal;

import app.senyap.identity.internal.AliasGenerator.Alias;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.outbox.OutboxWriter;
import app.senyap.shared.ratelimit.RateLimiter;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Profil + alias (functional spec §1.1, FS-01): suggest (belum disimpan) → simpan dengan
 * {@code suggestion_token}, batas ganti 1× per 7 hari kecuali saat onboarding, {@code alias_history}.
 */
@Service
class AliasService {

    record Profile(UUID publicId, String name, String tag, String color, Instant aliasChangeableAt, boolean onboarded) {
    }

    record Suggestion(String name, String tag, String color, String token) {
    }

    private record CurrentUser(UUID publicId, String name, String tag, Instant aliasChangedAt, boolean onboarded) {
    }

    private static final String SUGGESTION_KEY = "identity:alias-suggestion:";

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final AliasGenerator generator;
    private final StringRedisTemplate redis;
    private final RateLimiter rateLimiter;
    private final Tsid tsid;
    private final IdentityProperties props;
    private final SecureRandom random = new SecureRandom();

    AliasService(@Qualifier("identityDatabase") ModuleDatabase db, @Qualifier("identityOutbox") OutboxWriter outbox,
                 AliasGenerator generator, StringRedisTemplate redis, RateLimiter rateLimiter, Tsid tsid,
                 IdentityProperties props) {
        this.db = db;
        this.outbox = outbox;
        this.generator = generator;
        this.redis = redis;
        this.rateLimiter = rateLimiter;
        this.tsid = tsid;
        this.props = props;
    }

    Profile profile(long userId) {
        return db.jdbc().sql("""
                        SELECT public_id, alias_name, alias_tag, alias_color, alias_changed_at, onboarded_at IS NOT NULL AS onboarded
                        FROM users WHERE id = :id""")
                .param("id", userId)
                .query((rs, n) -> {
                    boolean onboarded = rs.getBoolean("onboarded");
                    return new Profile(rs.getObject("public_id", UUID.class), rs.getString("alias_name"),
                            rs.getString("alias_tag"), rs.getString("alias_color"),
                            changeableAt(onboarded, rs.getTimestamp("alias_changed_at").toInstant()), onboarded);
                })
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** {@code null} = boleh diganti sekarang. Dibulatkan ke atas ke menit (A5: tanpa timestamp presisi). */
    private Instant changeableAt(boolean onboarded, Instant aliasChangedAt) {
        if (!onboarded) {
            return null;
        }
        Instant at = aliasChangedAt.plus(props.aliasChangeInterval());
        if (!at.isAfter(Instant.now())) {
            return null;
        }
        long minute = 60;
        long s = at.getEpochSecond();
        return Instant.ofEpochSecond(((s + minute - 1) / minute) * minute);
    }

    Suggestion suggest(long userId, String animal, String color) {
        rateLimiter.hit("identity:alias-suggest:" + userId, props.suggestPerMinute(), Duration.ofMinutes(1));
        Alias alias = generator.generate(blankToNull(animal), blankToNull(color));
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        redis.opsForValue().set(SUGGESTION_KEY + token,
                userId + "|" + alias.animal() + "|" + alias.color().name() + "|" + alias.tag(), props.suggestionTtl());
        return new Suggestion(alias.name(), alias.tag(), alias.color().hex(), token);
    }

    Profile change(long userId, String token) {
        String stored = token == null || token.isBlank() ? null : redis.opsForValue().getAndDelete(SUGGESTION_KEY + token);
        if (stored == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Saran alias sudah kedaluwarsa. Acak lagi.");
        }
        String[] parts = stored.split("\\|");
        if (parts.length != 4 || Long.parseLong(parts[0]) != userId) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Saran alias sudah kedaluwarsa. Acak lagi.");
        }
        var color = AliasCatalog.color(parts[2]).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED));
        var alias = new Alias(parts[1], color, parts[3]);
        for (int i = 0; i < AliasGenerator.MAX_TAG_TRIES; i++) {
            try {
                apply(userId, alias);
                return profile(userId);
            } catch (DuplicateKeyException e) {
                // Pasangan nama+tag diambil orang lain sejak saran dibuat: nama tetap, tag diacak ulang.
                alias = new Alias(alias.animal(), alias.color(), generator.randomTag());
            }
        }
        throw new ApiException(ErrorCode.INTERNAL, "Alias tidak tersedia. Coba kombinasi lain.");
    }

    private void apply(long userId, Alias alias) {
        db.tx().executeWithoutResult(s -> {
            var user = db.jdbc().sql("""
                            SELECT public_id, alias_name, alias_tag, alias_changed_at, onboarded_at IS NOT NULL AS onboarded
                            FROM users WHERE id = :id FOR UPDATE""")
                    .param("id", userId)
                    .query((rs, n) -> new CurrentUser(rs.getObject("public_id", UUID.class), rs.getString("alias_name"),
                            rs.getString("alias_tag"), rs.getTimestamp("alias_changed_at").toInstant(),
                            rs.getBoolean("onboarded")))
                    .optional()
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            var now = Instant.now();
            if (user.onboarded()) {
                Instant allowedAt = user.aliasChangedAt().plus(props.aliasChangeInterval());
                if (now.isBefore(allowedAt)) {
                    throw ApiException.withRetryAfter(ErrorCode.ALIAS_CHANGE_TOO_SOON, Duration.between(now, allowedAt));
                }
            }
            var ts = Timestamp.from(now);
            boolean same = user.name().equals(alias.name()) && user.tag().equals(alias.tag());
            db.jdbc().sql("""
                            UPDATE users SET alias_name = :name, alias_tag = :tag, alias_color = :color,
                                             alias_changed_at = :now, onboarded_at = COALESCE(onboarded_at, :now)
                            WHERE id = :id""")
                    .param("name", alias.name()).param("tag", alias.tag()).param("color", alias.color().hex())
                    .param("now", ts).param("id", userId)
                    .update();
            if (!same) {
                AliasHistory.closeCurrent(db, userId, ts);
                AliasHistory.open(db, tsid, userId, alias.name(), alias.tag(), ts);
                outbox.append("user.alias_changed", Long.toString(userId), Map.of(
                        "user_id", userId, "public_id", user.publicId().toString(),
                        "name", alias.name(), "tag", alias.tag(), "color", alias.color().hex()));
            }
        });
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
