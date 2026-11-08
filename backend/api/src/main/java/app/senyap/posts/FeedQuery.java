package app.senyap.posts;

/**
 * Query feed (functional spec FS-03).
 *
 * @param category kategori (null = semua)
 * @param nearCell geohash sel viewer (≥ 5 karakter, dipotong ke 5); post di sel itu + 8 tetangganya.
 *                 null = tanpa filter lokasi
 * @param beforeId cursor: hanya post dengan ID lebih kecil (null = halaman pertama)
 * @param limit    jumlah maksimal ID
 */
public record FeedQuery(String category, String nearCell, Long beforeId, int limit) {
}
