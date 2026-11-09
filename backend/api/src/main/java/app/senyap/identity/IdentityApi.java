package app.senyap.identity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Membuat akun baru tanpa nomor HP (daftar dengan email + kata sandi; kredensialnya disimpan modul auth).
     * Alias acak dari katalog {@code unspecified}, {@code age_confirmed_at = now}, event {@code user.registered}.
     */
    SignInResult createAccount();

    /**
     * Login ulang berdasarkan user ID (kredensial non-HP yang diverifikasi modul pemanggil). Akun {@code DELETED}
     * yang belum di-purge dipulihkan seperti {@link #signIn}. Kosong bila user ID tidak dikenal atau akunnya sudah
     * di-purge (modul pemanggil sebaiknya melupakan kredensialnya).
     */
    Optional<SignInResult> signInAccount(long userId);

    /** Status + public_id per user ID. User ID yang tidak dikenal tidak ada di map. */
    Map<Long, AccountSummary> findAccounts(List<Long> userIds);

    /**
     * Identitas publik (public_id + alias + gender + avatar) per user ID, untuk ditampilkan modul lain (post, balasan, DM).
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

    /**
     * Terapkan sinyal perilaku ke trust score (FS-08). Idempotent per {@link TrustAdjustment#key()}. Skor
     * dijepit 0–100. Akun {@code ACTIVE} yang skornya turun ke {@link AccountStanding#AUTO_RESTRICT_AT} atau
     * lebih rendah otomatis {@code RESTRICTED} (shadow) selama 7 hari.
     */
    void adjustTrust(List<TrustAdjustment> adjustments);

    /**
     * Sanksi moderator/sistem (FS-09). Akun {@code DELETED} tidak diubah. Menerbitkan event
     * {@code user.sanctioned}. {@code BANNED} langsung memutus sesi (refresh ditolak; access token paling lama
     * sampai cache principal kedaluwarsa).
     */
    void applySanction(long userId, Sanction sanction);

    /** Data akun untuk peran legal (permintaan hukum). Tidak untuk client app. */
    Map<Long, AccountRecord> findRecords(List<Long> userIds);
}
