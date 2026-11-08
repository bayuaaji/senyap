package app.senyap.safety;

/**
 * Hasil moderasi otomatis (FS-08).
 * <ul>
 *   <li>{@code ALLOW}: terbit;</li>
 *   <li>{@code FLAG}: terbit + masuk antrean moderator (skor sedang, atau risiko menyakiti diri);</li>
 *   <li>{@code HOLD}: ditahan (hanya terlihat penulis) + antrean, sampai moderator memutuskan (skor tinggi);</li>
 *   <li>{@code REJECT}: ditolak dengan pesan ramah ({@code CONTENT_REJECTED}).</li>
 * </ul>
 */
public enum ScreeningDecision {
    ALLOW, FLAG, HOLD, REJECT
}
