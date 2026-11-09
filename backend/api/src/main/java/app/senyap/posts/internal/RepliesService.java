package app.senyap.posts.internal;

import app.senyap.identity.IdentityApi;
import app.senyap.identity.PublicIdentity;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.outbox.OutboxWriter;
import app.senyap.shared.ratelimit.RateLimiter;
import app.senyap.safety.ContentType;
import app.senyap.safety.TextVerdict;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Balasan (functional spec FS-04): alias akun penulis (dibaca dari identity saat render, tidak disalin) +
 * lencana Penulis untuk pembuat post. Urutan terlama → terbaru.
 */
@Service
class RepliesService {

    static final int PAGE_SIZE = 30;

    record ReplyAuthor(@JsonProperty("public_id") UUID publicId, @JsonProperty("name") String name,
                       @JsonProperty("tag") String tag, @JsonProperty("color") String color,
                       @JsonProperty("gender") String gender, @JsonProperty("avatar") String avatar,
                       @JsonProperty("is_post_author") boolean isPostAuthor) {
    }

    /** {@code is_hidden} hanya {@code true} untuk pembuat post (yang menyembunyikan) dan penulis balasan. */
    record ReplyView(@JsonProperty("id") String id, @JsonProperty("post_id") String postId,
                     @JsonProperty("author") ReplyAuthor author, @JsonProperty("body") String body,
                     @JsonProperty("created_at") Instant createdAt, @JsonProperty("is_mine") boolean isMine,
                     @JsonProperty("can_whisper") boolean canWhisper, @JsonProperty("is_hidden") boolean isHidden,
                     @JsonProperty("support_card") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean supportCard) {

        /** Hanya di respons membuat balasan, untuk penulisnya (FS-08 kartu bantuan). */
        ReplyView withSupportCard() {
            return new ReplyView(id, postId, author, body, createdAt, isMine, canWhisper, isHidden, Boolean.TRUE);
        }
    }

    private record ReplyRow(long id, long postId, long authorId, String body, Instant createdAt, boolean hidden,
                            boolean authorPurged) {
    }

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final Tsid tsid;
    private final IdentityApi identity;
    private final PostsService posts;
    private final RateLimiter rateLimiter;
    private final PostsProperties props;

    RepliesService(@Qualifier("postsDatabase") ModuleDatabase db, @Qualifier("postsOutbox") OutboxWriter outbox,
                   Tsid tsid, IdentityApi identity, PostsService posts, RateLimiter rateLimiter, PostsProperties props) {
        this.db = db;
        this.outbox = outbox;
        this.tsid = tsid;
        this.identity = identity;
        this.posts = posts;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    List<ReplyView> list(long viewerId, long postId, Long afterId, int limitPlusOne) {
        if (!posts.visibleTo(postId, viewerId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        long postAuthor = posts.authorOf(postId);
        // Balasan tersembunyi (oleh pembuat post) hanya terlihat oleh pembuat post dan penulisnya sendiri.
        var rows = db.jdbc().sql("""
                        SELECT id, post_id, author_id, body, created_at, hidden_by_post_author_at IS NOT NULL AS hidden,
                               author_purged_at IS NOT NULL AS purged
                        FROM replies
                        WHERE post_id = :post AND id > :after AND deleted_at IS NULL
                          AND (visibility = 'PUBLIC' OR (visibility = 'AUTHOR_ONLY' AND author_id = :viewer))
                          AND (hidden_by_post_author_at IS NULL OR :viewer = :postAuthor OR author_id = :viewer)
                        ORDER BY id LIMIT :limit""")
                .param("post", postId).param("after", afterId == null ? 0L : afterId).param("viewer", viewerId)
                .param("postAuthor", postAuthor).param("limit", limitPlusOne)
                .query((rs, n) -> new ReplyRow(rs.getLong("id"), rs.getLong("post_id"), rs.getLong("author_id"),
                        rs.getString("body"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("hidden"),
                        rs.getBoolean("purged")))
                .list();
        // Block dua arah: balasan dari/ke akun yang saling memblokir tidak tampil.
        var blocked = posts.blockedAmong(viewerId, rows.stream().map(ReplyRow::authorId).toList());
        if (!blocked.isEmpty()) {
            rows = rows.stream().filter(r -> !blocked.contains(r.authorId())).toList();
        }
        Map<Long, PublicIdentity> ids = identity.resolveIdentities(
                rows.stream().filter(r -> !r.authorPurged()).map(ReplyRow::authorId).distinct().toList());
        return rows.stream().map(r -> view(r, postAuthor, viewerId, ids)).toList();
    }

    ReplyView create(long userId, long postId, String rawBody) {
        String body = rawBody == null ? "" : rawBody.strip();
        int len = body.codePointCount(0, body.length());
        if (len < 1 || len > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Balasan harus 1–500 karakter.");
        }
        if (!posts.visibleTo(postId, userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        var standing = posts.writer(userId);
        TextVerdict verdict = posts.screen(ContentType.REPLY, body);
        Quotas.daily(rateLimiter, "reply", userId, standing, props.repliesPerDay(), props.repliesPerDayNewAccount(),
                "REPLY", "Kamu sudah mencapai batas balasan hari ini.");
        String visibility = PostsService.initialVisibility(standing, verdict);
        boolean counted = "PUBLIC".equals(visibility);
        long id = tsid.next();
        Instant now = Instant.now();
        db.tx().executeWithoutResult(s -> {
            db.jdbc().sql("""
                            INSERT INTO replies (id, post_id, author_id, body, visibility, created_at)
                            VALUES (:id, :post, :author, :body, :vis, :now)""")
                    .param("vis", visibility).param("id", id).param("post", postId).param("author", userId).param("body", body)
                    .param("now", Timestamp.from(now))
                    .update();
            if (counted) {
                db.jdbc().sql("UPDATE posts SET reply_count = reply_count + 1 WHERE id = :post").param("post", postId)
                        .update();
            }
            outbox.append("reply.created", Long.toString(postId), Map.of("reply_id", id, "post_id", postId));
        });
        long postAuthor = posts.authorOf(postId);
        var row = new ReplyRow(id, postId, userId, body, now, false, false);
        var view = view(row, postAuthor, userId, identity.resolveIdentities(List.of(userId)));
        return verdict.selfHarm() ? view.withSupportCard() : view;
    }

    void delete(long userId, long replyId) {
        var row = visibleRow(userId, replyId);
        if (row.authorId() != userId) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        db.tx().executeWithoutResult(s -> {
            int n = db.jdbc().sql("""
                            UPDATE replies SET deleted_at = now() WHERE id = :id AND deleted_at IS NULL
                            RETURNING hidden_by_post_author_at IS NULL AND visibility = 'PUBLIC' AS counted""")
                    .param("id", replyId).query(Boolean.class).optional()
                    .map(counted -> {
                        if (counted) {
                            decrementReplyCount(row.postId());
                        }
                        return 1;
                    }).orElse(0);
            if (n == 1) {
                outbox.append("reply.deleted", Long.toString(row.postId()), Map.of("reply_id", replyId, "post_id", row.postId()));
            }
        });
    }

    /** Pembuat post menyembunyikan / menampilkan lagi balasan di post-nya. Idempotent. */
    void setHidden(long userId, long replyId, boolean hidden) {
        var row = visibleRow(userId, replyId);
        Long postAuthor = posts.authorOf(row.postId());
        if (postAuthor == null || postAuthor != userId) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        db.tx().executeWithoutResult(s -> {
            int n = db.jdbc().sql(hidden
                            ? "UPDATE replies SET hidden_by_post_author_at = now() WHERE id = :id AND hidden_by_post_author_at IS NULL"
                            : "UPDATE replies SET hidden_by_post_author_at = NULL WHERE id = :id AND hidden_by_post_author_at IS NOT NULL")
                    .param("id", replyId).update();
            if (n == 1) {
                if (hidden) {
                    decrementReplyCount(row.postId());
                } else {
                    db.jdbc().sql("UPDATE posts SET reply_count = reply_count + 1 WHERE id = :post")
                            .param("post", row.postId()).update();
                }
            }
        });
    }

    private void decrementReplyCount(long postId) {
        db.jdbc().sql("UPDATE posts SET reply_count = GREATEST(0, reply_count - 1) WHERE id = :post")
                .param("post", postId).update();
    }

    /** Balasan yang terlihat oleh viewer (dan post-nya juga terlihat), atau NOT_FOUND. */
    private ReplyRow visibleRow(long viewerId, long replyId) {
        var row = db.jdbc().sql("""
                        SELECT id, post_id, author_id, body, created_at, hidden_by_post_author_at IS NOT NULL AS hidden,
                               author_purged_at IS NOT NULL AS purged
                        FROM replies WHERE id = :id AND deleted_at IS NULL
                          AND (visibility = 'PUBLIC' OR (visibility = 'AUTHOR_ONLY' AND author_id = :viewer))""")
                .param("id", replyId).param("viewer", viewerId)
                .query((rs, n) -> new ReplyRow(rs.getLong("id"), rs.getLong("post_id"), rs.getLong("author_id"),
                        rs.getString("body"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("hidden"),
                        rs.getBoolean("purged")))
                .optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!posts.visibleTo(row.postId(), viewerId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private static ReplyView view(ReplyRow r, long postAuthor, long viewerId, Map<Long, PublicIdentity> ids) {
        boolean mine = r.authorId() == viewerId;
        boolean isPostAuthor = r.authorId() == postAuthor;
        PublicIdentity p = r.authorPurged() ? null : ids.get(r.authorId());
        var author = p == null
                ? new ReplyAuthor(null, PostsService.DELETED_NAME, null, null, null, null, false)
                : new ReplyAuthor(p.publicId(), p.name(), p.tag(), p.color(), p.gender().code(), p.avatar(),
                        isPostAuthor);
        return new ReplyView(Tsid.toString(r.id()), Tsid.toString(r.postId()), author, r.body(),
                PostsService.minute(r.createdAt()), mine, !mine && !r.authorPurged() && !r.hidden(), r.hidden(), null);
    }
}
