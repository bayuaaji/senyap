package app.senyap.safety.internal;

import app.senyap.safety.ContentSnapshot;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Isi kolom {@code reports.snapshot} (JSONB). User ID tidak disalin: pengirim pesan disimpan sebagai peran
 * relatif ({@code TERLAPOR}/{@code PELAPOR}/{@code LAINNYA}/{@code SISTEM}).
 */
record SnapshotDoc(@JsonProperty("text") String text, @JsonProperty("image_url") String imageUrl,
                   @JsonProperty("visibility") String visibility, @JsonProperty("post_id") long postId,
                   @JsonProperty("reported_seq") long reportedSeq, @JsonProperty("created_at") Instant createdAt,
                   @JsonProperty("context") List<Msg> context) {

    record Msg(@JsonProperty("seq") long seq, @JsonProperty("who") String who, @JsonProperty("type") String type,
               @JsonProperty("body") String body, @JsonProperty("retracted") boolean retracted,
               @JsonProperty("created_at") Instant createdAt) {
    }

    static SnapshotDoc of(ContentSnapshot s, Long reporterUserId) {
        var ctx = s.context().stream().map(m -> new Msg(m.seq(), who(m, s.ownerUserId(), reporterUserId), m.type(),
                m.body(), m.retracted(), m.createdAt())).toList();
        return new SnapshotDoc(s.text(), s.imageUrl(), s.visibility(), s.postId(), s.ref().seq(), s.createdAt(), ctx);
    }

    private static String who(ContentSnapshot.Message m, long owner, Long reporter) {
        if ("SYSTEM".equals(m.type())) {
            return "SISTEM";
        }
        if (m.senderUserId() == owner) {
            return "TERLAPOR";
        }
        return reporter != null && m.senderUserId() == reporter ? "PELAPOR" : "LAINNYA";
    }
}
