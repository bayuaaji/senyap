package app.senyap.chat;

import java.util.List;
import java.util.Map;

/**
 * Port modul chat untuk validasi asal Bisik (functional spec FS-05 §5.1): post/balasan yang masih bisa
 * dibisiki + pemiliknya (= penerima). Batch, DTO record (aturan M3/M4).
 *
 * <p>Diimplementasikan modul posts (Fase 3, {@code PostsApi.findOrigins}) lewat adapter. Sampai itu digabung,
 * implementasinya SEMENTARA: {@code chat.internal.SeedOriginDirectory} (tabel {@code chat.dev_origins}).
 */
public interface OriginDirectory {

    /** Asal yang tidak ada / tidak bisa dibisiki tidak ada di map (pemanggil menjawab {@code NOT_FOUND}). */
    Map<OriginKey, OriginInfo> findOrigins(List<OriginKey> keys);

    /** @param type {@code POST} atau {@code REPLY} (sama dengan {@code chat.conversations.origin_type}) */
    record OriginKey(String type, long id) {
    }

    /**
     * @param ownerUserId pemilik post/balasan; hanya untuk server
     * @param excerpt     cuplikan ≤ 80 karakter untuk tab Permintaan / header chat
     */
    record OriginInfo(OriginKey key, long ownerUserId, String excerpt) {
    }
}
