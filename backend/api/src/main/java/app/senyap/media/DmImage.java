package app.senyap.media;

import java.time.Instant;

/**
 * Gambar DM siap tampil. {@code ownerUserId} hanya untuk pemeriksaan di server (pengirim harus pemiliknya);
 * jangan dikirim ke client.
 *
 * @param url       presigned GET, berlaku sampai {@code urlExpiresAt}
 */
public record DmImage(long mediaId, long ownerUserId, String mimeType, String url, Instant urlExpiresAt) {
}
