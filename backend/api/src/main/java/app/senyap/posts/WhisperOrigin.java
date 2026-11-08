package app.senyap.posts;

/**
 * Asal Bisik yang valid (hasil {@link PostsApi#findOrigins}).
 *
 * @param ref         asal yang diminta
 * @param postId      post tempat asal berada (= {@code ref.id()} untuk {@code POST})
 * @param ownerUserId pemilik post/balasan = penerima Bisik. Hanya untuk server; jangan dikirim ke client.
 * @param excerpt     cuplikan isi (≤ 80 karakter) untuk konteks di layar request Bisik
 */
public record WhisperOrigin(OriginRef ref, long postId, long ownerUserId, String excerpt) {
}
