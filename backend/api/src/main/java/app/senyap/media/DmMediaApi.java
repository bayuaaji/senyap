package app.senyap.media;

import java.util.List;
import java.util.Map;

/**
 * Gambar di Bisik/DM (functional spec FS-05 §5.2). Objek disimpan privat ({@code dm/…}); URL baca berupa
 * presigned GET berumur pendek, jadi gambar DM tidak pernah bisa dibuka lewat URL publik.
 *
 * <p>Fase 2: upload → {@code READY} tanpa pipeline moderasi. Moderasi gambar + penyatuan dengan pipeline
 * latar Fase 3 menyusul (Fase 4).
 */
public interface DmMediaApi {

    /** Gambar DM yang sudah {@code READY} per media ID. Yang tidak ada / belum selesai tidak ada di map. */
    Map<Long, DmImage> findDmImages(List<Long> mediaIds);
}
