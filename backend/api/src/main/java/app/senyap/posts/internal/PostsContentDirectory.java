package app.senyap.posts.internal;

import app.senyap.media.Background;
import app.senyap.media.MediaApi;
import app.senyap.safety.ContentDirectory;
import app.senyap.safety.ContentRef;
import app.senyap.safety.ContentSnapshot;
import app.senyap.safety.ContentType;
import app.senyap.safety.ContentVisibility;
import app.senyap.shared.db.ModuleDatabase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Port konten safety untuk post dan balasan: snapshot report, akun di balik konten (block/report), keputusan
 * moderator. Pelapor harus bisa melihat kontennya (visibility; block tidak dicek supaya "lapor lalu blokir" jalan).
 */
@Component
class PostsContentDirectory implements ContentDirectory {

    private final ModuleDatabase db;
    private final MediaApi media;

    PostsContentDirectory(@Qualifier("postsDatabase") ModuleDatabase db, MediaApi media) {
        this.db = db;
        this.media = media;
    }

    @Override
    public Set<ContentType> supports() {
        return Set.of(ContentType.POST, ContentType.REPLY);
    }

    @Override
    public Map<ContentRef, ContentSnapshot> snapshot(List<ContentRef> refs, Long viewerUserId) {
        var out = new HashMap<ContentRef, ContentSnapshot>();
        String viewerClause = viewerUserId == null ? "" : " AND " + PostsService.VISIBLE_TO_VIEWER;
        long viewer = viewerUserId == null ? 0 : viewerUserId;
        var postIds = refs.stream().filter(r -> r.type() == ContentType.POST).map(ContentRef::id).distinct().toList();
        var replyIds = refs.stream().filter(r -> r.type() == ContentType.REPLY).map(ContentRef::id).distinct().toList();
        if (!postIds.isEmpty()) {
            record Row(long id, long author, String body, long bg, String vis, java.time.Instant at) {
            }
            var rows = db.jdbc().sql("""
                            SELECT id, author_id, body, bg_media_id, visibility, created_at FROM posts
                            WHERE id IN (:ids)""" + (viewerUserId == null ? "" : " AND deleted_at IS NULL") + viewerClause)
                    .param("ids", postIds).param("viewer", viewer)
                    .query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                            rs.getString(5), rs.getTimestamp(6).toInstant()))
                    .list();
            Map<Long, Background> bgs = media.findBackgrounds(rows.stream().map(Row::bg).distinct().toList());
            for (Row r : rows) {
                var bg = bgs.get(r.bg());
                var ref = ContentRef.of(ContentType.POST, r.id());
                out.put(ref, new ContentSnapshot(ref, r.author(), r.id(), r.body(), bg == null ? null : bg.url(),
                        List.of(), r.vis(), r.at()));
            }
        }
        if (!replyIds.isEmpty()) {
            String clause = viewerUserId == null ? "" : """
                     AND r.deleted_at IS NULL
                     AND (r.visibility = 'PUBLIC' OR (r.visibility = 'AUTHOR_ONLY' AND r.author_id = :viewer))
                     AND p.deleted_at IS NULL
                     AND (p.visibility = 'PUBLIC' OR (p.visibility = 'AUTHOR_ONLY' AND p.author_id = :viewer))""";
            db.jdbc().sql("""
                            SELECT r.id, r.post_id, r.author_id, r.body, r.visibility, r.created_at
                            FROM replies r JOIN posts p ON p.id = r.post_id WHERE r.id IN (:ids)""" + clause)
                    .param("ids", replyIds).param("viewer", viewer)
                    .query((rs, n) -> new ContentSnapshot(ContentRef.of(ContentType.REPLY, rs.getLong(1)),
                            rs.getLong(3), rs.getLong(2), rs.getString(4), null, List.of(), rs.getString(5),
                            rs.getTimestamp(6).toInstant()))
                    .list().forEach(s -> out.put(s.ref(), s));
        }
        // Hanya ref yang diminta (seq selalu 0 untuk post/balasan).
        var result = new HashMap<ContentRef, ContentSnapshot>();
        for (ContentRef r : new ArrayList<>(refs)) {
            var s = out.get(ContentRef.of(r.type(), r.id()));
            if (s != null) {
                result.put(r, s);
            }
        }
        return result;
    }

    @Override
    public boolean moderate(ContentRef ref, ContentVisibility visibility) {
        String vis = visibility.name();
        return switch (ref.type()) {
            case POST -> db.tx().execute(s -> db.jdbc().sql("UPDATE posts SET visibility = :v WHERE id = :id")
                    .param("v", vis).param("id", ref.id()).update()) == 1;
            case REPLY -> Boolean.TRUE.equals(db.tx().execute(s -> {
                var prev = db.jdbc().sql("SELECT post_id, visibility, hidden_by_post_author_at IS NULL AND deleted_at IS NULL"
                                + " FROM replies WHERE id = :id FOR UPDATE")
                        .param("id", ref.id())
                        .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getBoolean(3)})
                        .optional().orElse(null);
                if (prev == null) {
                    return false;
                }
                db.jdbc().sql("UPDATE replies SET visibility = :v WHERE id = :id").param("v", vis)
                        .param("id", ref.id()).update();
                boolean wasCounted = "PUBLIC".equals(prev[1]) && (boolean) prev[2];
                boolean nowCounted = "PUBLIC".equals(vis) && (boolean) prev[2];
                if (wasCounted != nowCounted) {
                    db.jdbc().sql("UPDATE posts SET reply_count = GREATEST(0, reply_count + :d) WHERE id = :p")
                            .param("d", nowCounted ? 1 : -1).param("p", (long) prev[0]).update();
                }
                return true;
            }));
            default -> false;
        };
    }
}
