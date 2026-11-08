package app.senyap.safety;

import java.time.Instant;
import java.util.List;

/**
 * Salinan konten saat dilaporkan (FS-07). Hanya untuk server dan konsol moderator; user ID di sini tidak pernah
 * dikirim ke client app.
 *
 * @param ownerUserId akun di balik konten (untuk {@code CONVERSATION}: lawan bicara viewer)
 * @param postId      post induk (untuk balasan), atau post itu sendiri; 0 bila tidak relevan
 * @param context     pesan di sekitarnya (DM: hingga 20 pesan), urut seq naik; kosong untuk post/balasan
 * @param visibility  {@code PUBLIC}, {@code AUTHOR_ONLY}, {@code HIDDEN}, atau status percakapan untuk DM
 */
public record ContentSnapshot(ContentRef ref, long ownerUserId, long postId, String text, String imageUrl,
                              List<Message> context, String visibility, Instant createdAt) {

    /** Satu pesan DM di snapshot. Isi pesan yang sudah ditarik tetap disalin (bukti). */
    public record Message(long seq, long senderUserId, String type, String body, boolean retracted,
                          Instant createdAt) {
    }
}
