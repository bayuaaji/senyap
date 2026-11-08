package app.senyap.auth.internal;

/**
 * Pengirim kode OTP ke nomor HP (SMS/WhatsApp). Penyedia nyata belum dipilih (lihat PROGRESS.md,
 * pertanyaan terbuka): selama belum ada implementasi untuk profil produksi, aplikasi sengaja gagal start
 * di luar profil {@code local}/{@code test}. Implementasi tidak boleh menulis nomor HP atau kode ke log.
 */
interface OtpSender {

    enum Channel { SMS, WHATSAPP }

    void send(String e164Phone, String code, Channel channel);
}
