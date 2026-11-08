package app.senyap.posts;

import java.time.Instant;
import java.util.List;

/**
 * API publik modul posts (aturan M3/M4: batch, DTO record, tanpa transaksi lintas modul).
 * Dipakai modul feed (daftar + render post) dan modul chat (validasi asal Bisik).
 */
public interface PostsApi {

    /**
     * Validasi asal Bisik (functional spec FS-05 §5.1): untuk tiap post/balasan yang <b>masih bisa dibisiki</b>
     * (ada, belum dihapus, visibility {@code PUBLIC}; untuk balasan: belum disembunyikan pembuat post dan
     * post-nya juga masih bisa dibisiki) dikembalikan pemiliknya. Asal yang tidak memenuhi syarat tidak
     * ada di hasil, dan pemanggil sebaiknya menjawab {@code NOT_FOUND}. Pemanggil tetap wajib menolak Bisik
     * ke diri sendiri ({@link WhisperOrigin#ownerUserId()} == initiator) dan memeriksa block (Fase 4).
     */
    List<WhisperOrigin> findOrigins(List<OriginRef> refs);

    /**
     * ID post untuk feed (functional spec FS-03), terbaru dulu (ID = TSID berurut waktu). Hanya post
     * {@code PUBLIC} yang belum dihapus. Penyaringan per viewer dilakukan {@link #renderPosts}.
     */
    List<Long> feedPostIds(FeedQuery query);

    /** Post sejak {@code since} dengan {@code hot_score} tertinggi, untuk sorted set feed Populer. */
    List<HotCandidate> hotCandidates(Instant since, int limit);

    /**
     * Render post untuk {@code viewerUserId}, urutan sama dengan {@code postIds}. Post yang tidak boleh dilihat
     * viewer (dihapus, {@code HIDDEN}, {@code AUTHOR_ONLY} milik orang lain) dibuang dari hasil.
     */
    List<PostView> renderPosts(List<Long> postIds, long viewerUserId);
}
