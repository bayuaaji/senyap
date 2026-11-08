package app.senyap.identity;

import java.time.Duration;
import java.time.Instant;

/**
 * Kedudukan akun untuk kuota (functional spec §6). Hanya untuk server.
 *
 * @param trustScore 0–100, mulai 50
 */
public record AccountStanding(AccountStatus status, Instant createdAt, int trustScore) {

    /** Di bawah angka ini akun dianggap "trust rendah" (Bisik baru dinonaktifkan, DM 10/menit). */
    public static final int LOW_TRUST_BELOW = 20;

    /** Pada skor ini atau lebih rendah, akun {@code ACTIVE} otomatis di-shadow-restrict. */
    public static final int AUTO_RESTRICT_AT = 5;

    public static final Duration NEW_ACCOUNT_AGE = Duration.ofHours(24);

    public enum Tier { NEW, NORMAL, LOW_TRUST }

    public Tier tier(Instant now) {
        if (trustScore < LOW_TRUST_BELOW || status == AccountStatus.RESTRICTED) {
            return Tier.LOW_TRUST;
        }
        return createdAt.isAfter(now.minus(NEW_ACCOUNT_AGE)) ? Tier.NEW : Tier.NORMAL;
    }

    /** Boleh membuat konten baru (post, balasan, Bisik, pesan). {@code SUSPENDED} hanya bisa membaca. */
    public boolean canWrite() {
        return status == AccountStatus.ACTIVE || status == AccountStatus.RESTRICTED;
    }

    /** Shadow restriction: konten baru hanya terlihat oleh penulisnya sendiri. */
    public boolean shadowRestricted() {
        return status == AccountStatus.RESTRICTED;
    }
}
