package app.senyap.identity;

import java.util.List;
import java.util.Map;

/**
 * API publik modul identity (aturan M3/M4: batch, DTO record, tanpa transaksi lintas modul).
 * Hanya modul ini yang menerjemahkan user ID ↔ {@code public_id} / alias.
 */
public interface IdentityApi {

    /**
     * Login atau daftar berdasarkan {@code phone_hmac} (hex). Akun baru dibuat dengan alias acak dan
     * {@code age_confirmed_at = now}. Akun {@code DELETED} yang belum di-purge dipulihkan (penghapusan
     * dibatalkan, event {@code user.restored}); akun lain tidak diubah.
     */
    SignInResult signIn(String phoneHmacHex);

    /** Status + public_id per user ID. User ID yang tidak dikenal tidak ada di map. */
    Map<Long, AccountSummary> findAccounts(List<Long> userIds);

    /**
     * Identitas publik (public_id + alias) per user ID, untuk ditampilkan modul lain (post, balasan, DM).
     * User ID yang tidak dikenal tidak ada di map. Akun {@code DELETED} tetap dikembalikan dengan alias
     * terakhirnya; modul pemanggil yang memutuskan cara menampilkannya.
     */
    Map<Long, PublicIdentity> resolveIdentities(List<Long> userIds);

    /**
     * Menandai akun {@code DELETED} (functional spec FS-01, {@code DELETE /v1/me}) dan menerbitkan
     * event {@code user.deleted}. Purge dilakukan job worker setelah masa tenggang; login ulang sebelum itu
     * membatalkannya ({@link #signIn}). Akun {@code BANNED} tidak bisa dihapus. Idempotent.
     */
    void markDeleted(long userId);

    /**
     * Umur akun + trust score per user ID, untuk kuota bertingkat (functional spec §6: akun baru, normal,
     * trust rendah). User ID yang tidak dikenal tidak ada di map.
     */
    Map<Long, AccountStanding> findStandings(List<Long> userIds);
}
