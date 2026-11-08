package app.senyap.identity.internal;

import app.senyap.identity.AccountRecord;
import app.senyap.identity.AccountStanding;
import app.senyap.identity.AccountStatus;
import app.senyap.identity.Sanction;
import app.senyap.identity.TrustAdjustment;
import app.senyap.identity.AccountSummary;
import app.senyap.identity.IdentityApi;
import app.senyap.identity.PublicIdentity;
import app.senyap.identity.SignInResult;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.outbox.OutboxWriter;
import app.senyap.shared.security.PrincipalResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Implementasi {@link IdentityApi} + {@link PrincipalResolver}. Semua tulis lewat {@code identityTx} + outbox. */
@Service
class IdentityService implements IdentityApi, PrincipalResolver {

    private record Principal(long userId, AccountStatus status) {
    }

    private record UserRow(long id, UUID publicId, AccountStatus status, boolean onboarded) {
    }

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final AliasGenerator aliases;
    private final Tsid tsid;
    private final Cache<UUID, Principal> principals;

    IdentityService(@Qualifier("identityDatabase") ModuleDatabase db, @Qualifier("identityOutbox") OutboxWriter outbox,
                    AliasGenerator aliases, Tsid tsid, IdentityProperties props) {
        this.db = db;
        this.outbox = outbox;
        this.aliases = aliases;
        this.tsid = tsid;
        this.principals = Caffeine.newBuilder()
                .expireAfterWrite(props.principalCacheTtl())
                .maximumSize(100_000)
                .build();
    }

    @Override
    public SignInResult signIn(String phoneHmacHex) {
        byte[] phoneHmac = HexFormat.of().parseHex(phoneHmacHex);
        for (int attempt = 0; attempt < AliasGenerator.MAX_TAG_TRIES; attempt++) {
            var existing = findByPhone(phoneHmac);
            if (existing.isPresent()) {
                var u = existing.get();
                if (u.status() == AccountStatus.DELETED) {
                    var restored = restore(u);
                    if (restored.isPresent()) {
                        return new SignInResult(u.id(), u.publicId(), restored.get(), false, u.onboarded());
                    }
                    continue; // baru saja di-purge job worker: baca ulang (nomor sudah diputus → akun baru)
                }
                return new SignInResult(u.id(), u.publicId(), u.status(), false, u.onboarded());
            }
            try {
                return register(phoneHmac);
            } catch (DuplicateKeyException e) {
                // Alias bentrok (acak ulang) atau nomor yang sama sedang didaftarkan paralel (baca ulang).
            }
        }
        throw new IllegalStateException("Gagal membuat akun setelah beberapa percobaan");
    }

    private SignInResult register(byte[] phoneHmac) {
        var alias = aliases.generate(null, null);
        long id = tsid.next();
        var publicId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        db.tx().executeWithoutResult(s -> {
            db.jdbc().sql("""
                            INSERT INTO users (id, phone_hmac, public_id, alias_name, alias_tag, alias_color,
                                               alias_changed_at, age_confirmed_at, created_at)
                            VALUES (:id, :phone, :pub, :name, :tag, :color, :now, :now, :now)""")
                    .param("id", id).param("phone", phoneHmac).param("pub", publicId)
                    .param("name", alias.name()).param("tag", alias.tag()).param("color", alias.color().hex())
                    .param("now", now)
                    .update();
            AliasHistory.open(db, tsid, id, alias.name(), alias.tag(), now);
            outbox.append("user.registered", Long.toString(id), Map.of("user_id", id));
        });
        return new SignInResult(id, publicId, AccountStatus.ACTIVE, true, false);
    }

    /**
     * Login ulang sebelum purge membatalkan penghapusan: status kembali ke status sebelum dihapus
     * (biasanya {@code ACTIVE}), {@code deleted_at} dikosongkan sehingga job purge tidak memprosesnya,
     * dan event {@code user.restored} diterbitkan. {@code public_id} dan alias tidak berubah.
     *
     * @return status baru, atau kosong bila akun sudah di-purge di antara baca dan tulis
     */
    private Optional<AccountStatus> restore(UserRow u) {
        var status = db.tx().execute(s -> {
            var restored = db.jdbc().sql("""
                            UPDATE users SET status = COALESCE(deleted_from_status, 'ACTIVE'),
                                             deleted_at = NULL, deleted_from_status = NULL
                            WHERE id = :id AND status = 'DELETED' AND purged_at IS NULL
                            RETURNING status""")
                    .param("id", u.id()).query(String.class).optional().map(AccountStatus::valueOf);
            restored.ifPresent(st -> outbox.append("user.restored", Long.toString(u.id()), Map.of("user_id", u.id())));
            return restored;
        });
        principals.invalidate(u.publicId());
        return status;
    }

    private Optional<UserRow> findByPhone(byte[] phoneHmac) {
        return db.jdbc().sql("SELECT id, public_id, status, onboarded_at IS NOT NULL AS onboarded FROM users WHERE phone_hmac = :p")
                .param("p", phoneHmac)
                .query((rs, n) -> new UserRow(rs.getLong("id"), rs.getObject("public_id", UUID.class),
                        AccountStatus.valueOf(rs.getString("status")), rs.getBoolean("onboarded")))
                .optional();
    }

    @Override
    public Map<Long, AccountSummary> findAccounts(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return db.jdbc().sql("SELECT id, public_id, status, onboarded_at IS NOT NULL AS onboarded FROM users WHERE id IN (:ids)")
                .param("ids", List.copyOf(userIds))
                .query((rs, n) -> Map.entry(rs.getLong("id"), new AccountSummary(rs.getObject("public_id", UUID.class),
                        AccountStatus.valueOf(rs.getString("status")), rs.getBoolean("onboarded"))))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Map<Long, PublicIdentity> resolveIdentities(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return db.jdbc().sql("SELECT id, public_id, alias_name, alias_tag, alias_color FROM users WHERE id IN (:ids)")
                .param("ids", List.copyOf(userIds))
                .query((rs, n) -> Map.entry(rs.getLong("id"), new PublicIdentity(rs.getObject("public_id", UUID.class),
                        rs.getString("alias_name"), rs.getString("alias_tag"), rs.getString("alias_color"))))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public void markDeleted(long userId) {
        UUID publicId = db.tx().execute(s -> {
            var pub = db.jdbc().sql("""
                            UPDATE users SET status = 'DELETED', deleted_at = now(), deleted_from_status = status
                            WHERE id = :id AND status IN ('ACTIVE', 'RESTRICTED', 'SUSPENDED') RETURNING public_id""")
                    .param("id", userId).query(UUID.class).optional();
            pub.ifPresent(p -> outbox.append("user.deleted", Long.toString(userId), Map.of("user_id", userId)));
            return pub.orElse(null);
        });
        if (publicId != null) {
            principals.invalidate(publicId);
        }
    }

    @Override
    public OptionalLong resolveActiveUserId(UUID publicId) {
        var p = principals.get(publicId, key -> db.jdbc().sql("SELECT id, status FROM users WHERE public_id = :p")
                .param("p", key)
                .query((rs, n) -> new Principal(rs.getLong("id"), AccountStatus.valueOf(rs.getString("status"))))
                .optional().orElse(null));
        return p != null && p.status().canUseSession() ? OptionalLong.of(p.userId()) : OptionalLong.empty();
    }

    @Override
    public Map<Long, app.senyap.identity.AccountStanding> findStandings(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return db.jdbc().sql("SELECT id, status, created_at, trust_score FROM users WHERE id IN (:ids)")
                .param("ids", List.copyOf(userIds))
                .query((rs, n) -> Map.entry(rs.getLong("id"), new app.senyap.identity.AccountStanding(
                        AccountStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("trust_score"))))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ---- Fase 4: trust + sanksi ----------------------------------------------------------------

    static final Duration AUTO_RESTRICT_FOR = Duration.ofDays(7);

    @Override
    public void adjustTrust(List<TrustAdjustment> adjustments) {
        for (TrustAdjustment a : adjustments) {
            UUID restricted = db.tx().execute(s -> {
                int inserted = db.jdbc().sql("""
                                INSERT INTO trust_adjustments (adjustment_key, user_id, delta) VALUES (:k, :u, :d)
                                ON CONFLICT (adjustment_key) DO NOTHING""")
                        .param("k", a.key()).param("u", a.userId()).param("d", a.delta()).update();
                if (inserted == 0) {
                    return null;
                }
                var row = db.jdbc().sql("""
                                UPDATE users SET trust_score = LEAST(100, GREATEST(0, trust_score + :d))
                                WHERE id = :u RETURNING trust_score, status, public_id""")
                        .param("d", a.delta()).param("u", a.userId())
                        .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getObject(3, UUID.class)})
                        .optional().orElse(null);
                if (row == null || (int) row[0] > AccountStanding.AUTO_RESTRICT_AT || !"ACTIVE".equals(row[1])) {
                    return null;
                }
                db.jdbc().sql("UPDATE users SET status = 'RESTRICTED', sanction_until = :until WHERE id = :u")
                        .param("until", Timestamp.from(Instant.now().plus(AUTO_RESTRICT_FOR))).param("u", a.userId())
                        .update();
                outbox.append("user.sanctioned", Long.toString(a.userId()), Map.of("user_id", a.userId(),
                        "status", "RESTRICTED", "actor", "AUTO"));
                return (UUID) row[2];
            });
            if (restricted != null) {
                principals.invalidate(restricted);
            }
        }
    }

    @Override
    public void applySanction(long userId, Sanction sanction) {
        AccountStatus status = sanction.status();
        if (status == AccountStatus.DELETED) {
            throw new IllegalArgumentException("DELETED bukan sanksi");
        }
        Instant until = status == AccountStatus.RESTRICTED || status == AccountStatus.SUSPENDED ? sanction.until() : null;
        UUID publicId = db.tx().execute(s -> {
            var pub = db.jdbc().sql("""
                            UPDATE users SET status = :st, sanction_until = :until
                            WHERE id = :u AND status <> 'DELETED' RETURNING public_id""")
                    .param("st", status.name()).param("until", until == null ? null : Timestamp.from(until))
                    .param("u", userId).query(UUID.class).optional();
            pub.ifPresent(p -> outbox.append("user.sanctioned", Long.toString(userId),
                    Map.of("user_id", userId, "status", status.name(), "actor", "MODERATOR")));
            return pub.orElse(null);
        });
        if (publicId != null) {
            principals.invalidate(publicId);
        }
    }

    /** Job worker: sanksi berbatas waktu yang sudah lewat → {@code ACTIVE}. */
    int expireSanctions(int batch) {
        var expired = db.jdbc().sql("""
                        SELECT id, public_id FROM users
                        WHERE sanction_until IS NOT NULL AND sanction_until < now() AND status IN ('RESTRICTED', 'SUSPENDED')
                        ORDER BY sanction_until LIMIT :n""")
                .param("n", batch)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getObject(2, UUID.class))).list();
        for (var e : expired) {
            db.tx().executeWithoutResult(s -> {
                int n = db.jdbc().sql("""
                                UPDATE users SET status = 'ACTIVE', sanction_until = NULL
                                WHERE id = :u AND sanction_until < now() AND status IN ('RESTRICTED', 'SUSPENDED')""")
                        .param("u", e.getKey()).update();
                if (n == 1) {
                    outbox.append("user.sanctioned", Long.toString(e.getKey()),
                            Map.of("user_id", e.getKey(), "status", "ACTIVE", "actor", "AUTO"));
                }
            });
            principals.invalidate(e.getValue());
        }
        return expired.size();
    }

    @Override
    public Map<Long, AccountRecord> findRecords(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        var history = new HashMap<Long, List<String>>();
        db.jdbc().sql("""
                        SELECT user_id, alias_name, alias_tag FROM alias_history WHERE user_id IN (:ids)
                        ORDER BY valid_from DESC""")
                .param("ids", List.copyOf(userIds))
                .query(rs -> {
                    history.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(rs.getString(2) + " #" + rs.getString(3));
                });
        return db.jdbc().sql("""
                        SELECT id, public_id, alias_name, alias_tag, status, trust_score, phone_hmac, created_at,
                               sanction_until
                        FROM users WHERE id IN (:ids)""")
                .param("ids", List.copyOf(userIds))
                .query((rs, n) -> {
                    long id = rs.getLong("id");
                    var until = rs.getTimestamp("sanction_until");
                    return Map.entry(id, new AccountRecord(id, rs.getObject("public_id", UUID.class),
                            rs.getString("alias_name") + " #" + rs.getString("alias_tag"),
                            AccountStatus.valueOf(rs.getString("status")), rs.getInt("trust_score"),
                            HexFormat.of().formatHex(rs.getBytes("phone_hmac")),
                            rs.getTimestamp("created_at").toInstant(), until == null ? null : until.toInstant(),
                            List.copyOf(history.getOrDefault(id, List.of()))));
                })
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
