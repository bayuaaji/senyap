package app.senyap.safety;

import java.util.List;
import java.util.Set;

/** API publik modul safety (aturan M3/M4): block, moderasi otomatis. */
public interface SafetyApi {

    /**
     * User di antara {@code others} yang punya block dengan {@code userId} di <b>salah satu arah</b>
     * (memblokir atau diblokir). Hasil tidak membedakan arah, supaya pemanggil tidak membocorkannya.
     */
    Set<Long> blockedEitherWay(long userId, List<Long> others);

    /**
     * {@code blockerId} memblokir {@code blockedId}. Idempotent. Menerbitkan event {@code user.blocked}
     * (consumer chat menutup semua percakapan keduanya).
     *
     * @param conversationId percakapan asal block, boleh {@code null}
     */
    void block(long blockerId, long blockedId, Long conversationId);

    /** Filter kata + pola doxxing + klasifikasi teks (FS-08). Sinkron dan murah; dipanggil sebelum menyimpan. */
    TextVerdict screenText(TextScreening request);

    /** Hash matching + klasifikasi gambar (FS-08). */
    ImageVerdict screenImage(ImageScreening request);
}
