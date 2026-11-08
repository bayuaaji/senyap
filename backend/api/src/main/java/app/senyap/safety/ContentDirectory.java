package app.senyap.safety;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port yang diimplementasikan modul pemilik konten (posts, chat) supaya safety bisa membuat snapshot report,
 * menentukan akun di balik konten (block/report merujuk konten, bukan user ID), dan menjalankan keputusan
 * moderator, <b>tanpa</b> safety bergantung ke modul itu (arah dependensi: posts/chat → safety, tanpa siklus).
 * Semua method batch (aturan M4).
 */
public interface ContentDirectory {

    Set<ContentType> supports();

    /**
     * Snapshot konten. Bila {@code viewerUserId} tidak null, hanya konten yang boleh dilihat viewer itu yang
     * dikembalikan (pelapor harus bisa melihat konten yang ia laporkan; untuk DM: viewer anggota percakapan).
     * Konten yang tidak ada / tidak boleh dilihat tidak ada di map.
     */
    Map<ContentRef, ContentSnapshot> snapshot(List<ContentRef> refs, Long viewerUserId);

    /**
     * Keputusan moderator. @return {@code false} bila jenis konten ini tidak bisa disembunyikan (mis. pesan DM)
     * atau konten tidak ada.
     */
    boolean moderate(ContentRef ref, ContentVisibility visibility);
}
