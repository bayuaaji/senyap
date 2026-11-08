package app.senyap.safety;

/**
 * Sinyal gambar dari pipeline media (gambar tidak dikirim ke safety). Penyedia moderasi eksternal nanti bisa
 * menerima URL presigned lewat field tambahan.
 *
 * @param perceptualHashHex dHash 64-bit (16 hex) dari gambar <b>asli</b> (sebelum blur)
 * @param skinRatio         proporsi piksel warna kulit 0–1 (sinyal heuristik lokal)
 */
public record ImageScreening(String perceptualHashHex, double skinRatio) {
}
