package app.senyap.identity.internal;

import app.senyap.identity.Gender;
import app.senyap.identity.internal.AliasGenerator.Alias;
import app.senyap.identity.internal.AvatarCatalog.Avatar;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Profil + alias (functional spec §1.1, FS-01): suggest (belum disimpan, dari katalog gender user) → simpan
 * dengan {@code suggestion_token}, batas ganti 1× per 7 hari kecuali saat onboarding, {@code alias_history}.
 * Gender + avatar diganti lewat {@link #updateProfile} kapan saja, tanpa mengubah alias.
 */
@Service
class AliasService {

    record Profile(UUID publicId, String name, String tag, String color, Instant aliasChangeableAt, boolean onboarded,
                   Gender gender, Avatar avatar) {
    }

    /**
     * Perubahan profil. {@code gender} kosong = tidak diubah. {@code avatarChanged = false} = avatar tidak diubah;
     * {@code true} dengan {@code avatar} kosong = avatar dihapus.
     */
    record ProfileChange(Optional<Gender> gender, boolean avatarChanged, Optional<Avatar> avatar) {
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
                        SELECT public_id, alias_name, alias_tag, alias_color, alias_changed_at,
                               onboarded_at IS NOT NULL AS onboarded, gender, avatar
                        FROM users WHERE id = :id""")
                .param("id", userId)
                .query((rs, n) -> {
                    boolean onboarded = rs.getBoolean("onboarded");
                    return new Profile(rs.getObject("public_id", UUID.class), rs.getString("alias_name"),
                            rs.getString("alias_tag"), rs.getString("alias_color"),
                            changeableAt(onboarded, rs.getTimestamp("alias_changed_at").toInstant()), onboarded,
                            Gender.fromCode(rs.getString("gender")).orElse(Gender.UNSPECIFIED),
                            AvatarCatalog.find(rs.getString("avatar")).orElse(null));
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

    Gender gender(long userId) {
        return db.jdbc().sql("SELECT gender FROM users WHERE id = :id").param("id", userId).query(String.class)
                .optional().flatMap(Gender::fromCode).orElse(Gender.UNSPECIFIED);
    }

    /** Saran dari katalog gender user saat ini. Kata yang tidak ada di katalog itu → {@code VALIDATION_FAILED}. */
    Suggestion suggest(long userId, String first, String second) {
        rateLimiter.hit("identity:alias-suggest:" + userId, props.suggestPerMinute(), Duration.ofMinutes(1));
        Alias alias = generator.generate(gender(userId), blankToNull(first), blankToNull(second));
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        redis.opsForValue().set(SUGGESTION_KEY + token, String.join("|", Long.toString(userId),
                alias.gender().code(), alias.first(), alias.second().name(), alias.tag()), props.suggestionTtl());
        return new Suggestion(alias.name(), alias.tag(), alias.second().hex(), token);
    }

    /**
     * Menyimpan saran. Katalog yang dipakai adalah katalog saat saran dibuat (tersimpan di token), jadi mengganti
     * gender di antara suggest dan simpan tidak membatalkan saran.
     */
    Profile change(long userId, String token) {
        String stored = token == null || token.isBlank() ? null : redis.opsForValue().getAndDelete(SUGGESTION_KEY + token);
        if (stored == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Saran alias sudah kedaluwarsa. Acak lagi.");
        }
        String[] parts = stored.split("\\|");
        if (parts.length != 5 || Long.parseLong(parts[0]) != userId) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Saran alias sudah kedaluwarsa. Acak lagi.");
        }
        var gender = Gender.fromCode(parts[1]).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED));
        var second = AliasCatalog.words(gender).second(parts[3])
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED));
        var alias = new Alias(gender, parts[2], second, parts[4]);
        for (int i = 0; i < AliasGenerator.MAX_TAG_TRIES; i++) {
            try {
                apply(userId, alias);
                return profile(userId);
            } catch (DuplicateKeyException e) {
                // Pasangan nama+tag diambil orang lain sejak saran dibuat: nama tetap, tag diacak ulang.
                alias = new Alias(alias.gender(), alias.first(), alias.second(), generator.randomTag());
            }
        }
        throw new ApiException(ErrorCode.INTERNAL, "Alias tidak tersedia. Coba kombinasi lain.");
    }

    /** {@code PUT /v1/me/profile}: gender dan/atau avatar. Alias tidak berubah. Event {@code user.profile_changed}. */
    Profile updateProfile(long userId, ProfileChange change) {
        if (change.gender().isEmpty() && !change.avatarChanged()) {
            return profile(userId);
        }
        db.tx().executeWithoutResult(s -> {
            var publicId = db.jdbc().sql("""
                            UPDATE users SET gender = COALESCE(CAST(:gender AS VARCHAR), gender),
                                             avatar = CASE WHEN :avatarChanged THEN CAST(:avatar AS VARCHAR) ELSE avatar END
                            WHERE id = :id RETURNING public_id""")
                    .param("gender", change.gender().map(Gender::code).orElse(null))
                    .param("avatarChanged", change.avatarChanged())
                    .param("avatar", change.avatar().map(Avatar::id).orElse(null))
                    .param("id", userId)
                    .query(UUID.class).optional()
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            // Envelope tidak menerima nilai null: yang dikirim hanya field yang berubah; nilainya dibaca lewat
            // IdentityApi.resolveIdentities (gender + avatar selalu dibaca saat render).
            var changed = new java.util.ArrayList<String>();
            change.gender().ifPresent(g -> changed.add("gender"));
            if (change.avatarChanged()) {
                changed.add("avatar");
            }
            outbox.append("user.profile_changed", Long.toString(userId), Map.of(
                    "user_id", userId, "public_id", publicId.toString(), "changed", List.copyOf(changed)));
        });
        return profile(userId);
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
                    .param("name", alias.name()).param("tag", alias.tag()).param("color", alias.second().hex())
                    .param("now", ts).param("id", userId)
                    .update();
            if (!same) {
                AliasHistory.closeCurrent(db, userId, ts);
                AliasHistory.open(db, tsid, userId, alias.name(), alias.tag(), ts);
                outbox.append("user.alias_changed", Long.toString(userId), Map.of(
                        "user_id", userId, "public_id", user.publicId().toString(),
                        "name", alias.name(), "tag", alias.tag(), "color", alias.second().hex()));
            }
        });
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
