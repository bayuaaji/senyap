package app.senyap.posts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * Bentuk respons post (functional spec §4) untuk satu viewer. {@code author} hanya berisi {@code public_id}
 * + alias, tidak pernah user ID. {@code created_at} sudah dibulatkan ke menit.
 */
public record PostView(
        @JsonProperty("id") String id,
        @JsonProperty("author") Author author,
        @JsonProperty("body") String body,
        @JsonProperty("category") String category,
        @JsonProperty("background") BackgroundRef background,
        @JsonProperty("city") String city,
        @JsonProperty("heart_count") int heartCount,
        @JsonProperty("reply_count") int replyCount,
        @JsonProperty("viewer_has_hearted") boolean viewerHasHearted,
        @JsonProperty("is_mine") boolean isMine,
        @JsonProperty("can_whisper") boolean canWhisper,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("support_card") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean supportCard) {

    /**
     * {@code support_card = true} hanya di respons membuat post, bila tulisan terdeteksi berisiko menyakiti diri
     * (FS-08): client menampilkan kartu bantuan ke penulis. Tidak pernah dikirim ke viewer lain.
     */
    public PostView withSupportCard() {
        return new PostView(id, author, body, category, background, city, heartCount, replyCount, viewerHasHearted,
                isMine, canWhisper, createdAt, Boolean.TRUE);
    }

    /** Alias penulis. {@code publicId} null bila akun sudah di-purge (ditampilkan "[dihapus]"). */
    public record Author(@JsonProperty("public_id") UUID publicId, @JsonProperty("name") String name,
                         @JsonProperty("tag") String tag, @JsonProperty("color") String color) {
    }

    public record BackgroundRef(@JsonProperty("url") String url, @JsonProperty("blurhash") String blurhash) {
    }
}
