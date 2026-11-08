package app.senyap.identity;

/**
 * Perubahan trust score dari sinyal perilaku (functional spec FS-08). Idempotent per {@code key}.
 *
 * @param key   kunci unik sumber sinyal, mis. {@code "block:<event_id>"}; dipakai sekali
 * @param delta negatif = turun; skor akhir dijepit 0–100
 */
public record TrustAdjustment(long userId, int delta, String key) {
}
