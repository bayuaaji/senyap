package app.senyap.media;

import java.util.List;
import java.util.Map;

/** API publik modul media (aturan M3/M4: batch, DTO record). */
public interface MediaApi {

    /**
     * Latar yang siap dipakai ({@code READY}) per media ID. Media yang tidak ada, belum selesai diproses,
     * atau ditolak tidak ada di map. Pemanggil memeriksa {@link Background#ownerUserId()}: {@code null} =
     * galeri bawaan (boleh dipakai siapa saja), selain itu hanya boleh dipakai pemiliknya.
     */
    Map<Long, Background> findBackgrounds(List<Long> mediaIds);
}
