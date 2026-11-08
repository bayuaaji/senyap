package app.senyap.media;

/**
 * Gambar latar post yang sudah diproses. {@code ownerUserId} hanya untuk pemeriksaan di server
 * (null = galeri bawaan); jangan pernah dikirim ke client.
 */
public record Background(long mediaId, Long ownerUserId, String url, String blurhash, int width, int height) {
}
