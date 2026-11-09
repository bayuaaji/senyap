package app.senyap.auth.internal;

/**
 * Pengirim email transaksional (kode reset kata sandi). Penyedia nyata belum dipilih (lihat PROGRESS.md):
 * selama belum ada implementasi, aplikasi sengaja gagal start di luar profil {@code local}/{@code test}
 * ({@link EmailAuthService}). Implementasi tidak boleh menulis alamat email atau kode ke log/metrik.
 */
interface EmailSender {

    /** Kode reset 6 digit, berlaku {@code validMinutes} menit. Teks email dalam Bahasa Indonesia. */
    void sendPasswordResetCode(String email, String code, long validMinutes);
}
