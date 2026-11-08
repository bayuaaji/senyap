package app.senyap.posts.internal;

import app.senyap.identity.AccountStanding;
import app.senyap.identity.IdentityApi;
import app.senyap.identity.PublicIdentity;
import app.senyap.media.Background;
import app.senyap.media.MediaApi;
import app.senyap.posts.FeedQuery;
import app.senyap.posts.HotCandidate;
import app.senyap.posts.OriginRef;
import app.senyap.posts.OriginType;
import app.senyap.posts.PostView;
import app.senyap.posts.PostsApi;
import app.senyap.posts.WhisperOrigin;
import app.senyap.safety.ContentType;
import app.senyap.safety.SafetyApi;
import app.senyap.safety.ScreeningDecision;
import app.senyap.safety.TextScreening;
import app.senyap.safety.TextVerdict;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.outbox.OutboxWriter;
import app.senyap.shared.ratelimit.RateLimiter;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Post (functional spec FS-02) + implementasi {@link PostsApi}. Semua tulis lewat {@code postsTx} + outbox. */
@Service
class PostsService implements PostsApi {

    static final List<String> CATEGORIES =
            List.of("curhat", "cinta", "kerja", "kampus", "keluarga", "pengakuan", "tanya", "lainnya");
    static final String DELETED_NAME = "[dihapus]";

    /** Post terlihat oleh {@code :viewer}: belum dihapus, publik atau shadow-restricted miliknya sendiri. */
    static final String VISIBLE_TO_VIEWER =
            "deleted_at IS NULL AND (visibility = 'PUBLIC' OR (visibility = 'AUTHOR_ONLY' AND author_id = :viewer))";

    record NewPost(String body, String category, long bgMediaId, boolean useLocation, String geohash) {
    }

    private record PostRow(long id, long authorId, String body, long bgMediaId, String category, String city,
                           int heartCount, int replyCount, Instant createdAt) {
    }

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final Tsid tsid;
    private final IdentityApi identity;
    private final MediaApi media;
    private final RateLimiter rateLimiter;
    private final PostsProperties props;
    private final SafetyApi safety;

    PostsService(@Qualifier("postsDatabase") ModuleDatabase db, @Qualifier("postsOutbox") OutboxWriter outbox,
                 Tsid tsid, IdentityApi identity, MediaApi media, RateLimiter rateLimiter, PostsProperties props,
                 SafetyApi safety) {
        this.safety = safety;
        this.db = db;
        this.outbox = outbox;
        this.tsid = tsid;
        this.identity = identity;
        this.media = media;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    // ---- FS-02 ---------------------------------------------------------------------------------

    PostView create(long userId, NewPost req) {
        String body = req.body() == null ? "" : req.body().strip();
        int len = body.codePointCount(0, body.length());
        if (len < 1 || len > 250) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tulisan harus 1–250 karakter.");
        }
        if (!CATEGORIES.contains(req.category())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kategori tidak dikenal.");
        }
        Background bg = media.findBackgrounds(List.of(req.bgMediaId())).get(req.bgMediaId());
        if (bg == null || (bg.ownerUserId() != null && bg.ownerUserId() != userId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gambar latar tidak bisa dipakai.");
        }
        String geohash = null;
        String city = null;
        if (req.useLocation()) {
            geohash = Geohash.normalize(req.geohash());
            if (geohash == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Lokasi tidak valid.");
            }
            city = CityLookup.nearest(geohash);
        }
        AccountStanding standing = writer(userId);
        TextVerdict verdict = screen(ContentType.POST, body);
        Quotas.daily(rateLimiter, "post", userId, standing, props.postsPerDay(), props.postsPerDayNewAccount(), "POST",
                "Kamu sudah mencapai batas post hari ini.");
        String visibility = initialVisibility(standing, verdict);

        long id = tsid.next();
        Instant now = Instant.now();
        String g = geohash;
        String c = city;
        db.tx().executeWithoutResult(s -> {
            db.jdbc().sql("""
                            INSERT INTO posts (id, author_id, body, bg_media_id, category, geohash, city, hot_score,
                                               visibility, created_at)
                            VALUES (:id, :author, :body, :bg, :cat, :geo, :city,
                                    log(1) + extract(epoch FROM CAST(:now AS timestamptz)) / 45000, :vis, :now)""")
                    .param("vis", visibility)
                    .param("id", id).param("author", userId).param("body", body).param("bg", req.bgMediaId())
                    .param("cat", req.category()).param("geo", g).param("city", c)
                    .param("now", Timestamp.from(now))
                    .update();
            var payload = new HashMap<String, Object>();
            payload.put("post_id", id);
            payload.put("category", req.category());
            if (g != null) {
                payload.put("geohash", g);
            }
            outbox.append("post.created", Long.toString(id), payload);
        });
        PostView view = render(List.of(id), userId).getFirst();
        return verdict.selfHarm() ? view.withSupportCard() : view;
    }

    /**
     * Akun boleh menulis? {@code SUSPENDED} → FORBIDDEN. Dipakai post dan balasan.
     */
    AccountStanding writer(long userId) {
        var standing = identity.findStandings(List.of(userId)).get(userId);
        if (standing == null || !standing.canWrite()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Akunmu sedang ditangguhkan, jadi belum bisa menulis.");
        }
        return standing;
    }

    /** FS-08 sinkron: REJECT → CONTENT_REJECTED dengan pesan ramah. */
    TextVerdict screen(ContentType kind, String body) {
        TextVerdict v = safety.screenText(new TextScreening(kind, body));
        if (v.decision() == ScreeningDecision.REJECT) {
            throw new ApiException(ErrorCode.CONTENT_REJECTED, v.message());
        }
        return v;
    }

    /**
     * Shadow restriction atau ditahan moderasi otomatis (HOLD) → {@code AUTHOR_ONLY}: penulis tetap melihatnya
     * seperti biasa, orang lain tidak, sampai moderator memutuskan (pulihkan → PUBLIC, sembunyikan → HIDDEN).
     */
    static String initialVisibility(AccountStanding standing, TextVerdict verdict) {
        return standing.shadowRestricted() || verdict.decision() == ScreeningDecision.HOLD ? "AUTHOR_ONLY" : "PUBLIC";
    }

    PostView get(long viewerId, long postId) {
        var views = render(List.of(postId), viewerId);
        if (views.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return views.getFirst();
    }

    void delete(long userId, long postId) {
        var author = db.jdbc().sql("SELECT author_id FROM posts WHERE id = :id AND " + VISIBLE_TO_VIEWER)
                .param("id", postId).param("viewer", userId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (author != userId) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        db.tx().executeWithoutResult(s -> {
            int n = db.jdbc().sql("UPDATE posts SET deleted_at = now() WHERE id = :id AND deleted_at IS NULL")
                    .param("id", postId).update();
            if (n == 1) {
                outbox.append("post.deleted", Long.toString(postId), Map.of("post_id", postId));
            }
        });
    }

    /** Post sendiri (termasuk yang shadow-restricted), terbaru dulu. */
    List<PostView> mine(long userId, Long beforeId, int limitPlusOne) {
        var ids = db.jdbc().sql("""
                        SELECT id FROM posts WHERE author_id = :u AND deleted_at IS NULL AND visibility <> 'HIDDEN'
                          AND id < :before ORDER BY id DESC LIMIT :limit""")
                .param("u", userId).param("before", beforeId == null ? Long.MAX_VALUE : beforeId)
                .param("limit", limitPlusOne)
                .query(Long.class).list();
        return render(ids, userId);
    }

    /** {@code true} bila post terlihat oleh viewer; dipakai balasan dan hati. */
    boolean visibleTo(long postId, long viewerId) {
        var author = db.jdbc().sql("SELECT author_id FROM posts WHERE id = :id AND " + VISIBLE_TO_VIEWER)
                .param("id", postId).param("viewer", viewerId).query(Long.class).optional();
        return author.isPresent() && !blocked(viewerId, author.get());
    }

    /** Penulis di antara {@code authors} yang punya block dengan viewer (arah tidak dibedakan). */
    Set<Long> blockedAmong(long viewerId, List<Long> authors) {
        var others = authors.stream().filter(a -> a != viewerId).distinct().toList();
        return others.isEmpty() ? Set.of() : safety.blockedEitherWay(viewerId, others);
    }

    /** Block dua arah (FS-05 §5.2): konten saling tersembunyi; dari luar sama dengan "tidak ada". */
    boolean blocked(long viewerId, long authorId) {
        return authorId != viewerId && !safety.blockedEitherWay(viewerId, List.of(authorId)).isEmpty();
    }

    /** Pemilik post (tanpa cek visibility). */
    Long authorOf(long postId) {
        return db.jdbc().sql("SELECT author_id FROM posts WHERE id = :id").param("id", postId)
                .query(Long.class).optional().orElse(null);
    }

    // ---- PostsApi ------------------------------------------------------------------------------

    @Override
    public List<WhisperOrigin> findOrigins(List<OriginRef> refs) {
        var postIds = new ArrayList<Long>();
        var replyIds = new ArrayList<Long>();
        for (OriginRef r : refs) {
            (r.type() == OriginType.POST ? postIds : replyIds).add(r.id());
        }
        var out = new ArrayList<WhisperOrigin>();
        if (!postIds.isEmpty()) {
            db.jdbc().sql("""
                            SELECT id, author_id, body FROM posts
                            WHERE id IN (:ids) AND deleted_at IS NULL AND visibility = 'PUBLIC'""")
                    .param("ids", List.copyOf(Set.copyOf(postIds)))
                    .query((rs, n) -> new WhisperOrigin(new OriginRef(OriginType.POST, rs.getLong("id")),
                            rs.getLong("id"), rs.getLong("author_id"), excerpt(rs.getString("body"))))
                    .list().forEach(out::add);
        }
        if (!replyIds.isEmpty()) {
            db.jdbc().sql("""
                            SELECT r.id, r.post_id, r.author_id, r.body FROM replies r JOIN posts p ON p.id = r.post_id
                            WHERE r.id IN (:ids) AND r.deleted_at IS NULL AND r.visibility = 'PUBLIC'
                              AND r.hidden_by_post_author_at IS NULL AND r.author_purged_at IS NULL
                              AND p.deleted_at IS NULL AND p.visibility = 'PUBLIC'""")
                    .param("ids", List.copyOf(Set.copyOf(replyIds)))
                    .query((rs, n) -> new WhisperOrigin(new OriginRef(OriginType.REPLY, rs.getLong("id")),
                            rs.getLong("post_id"), rs.getLong("author_id"), excerpt(rs.getString("body"))))
                    .list().forEach(out::add);
        }
        return out;
    }

    @Override
    public List<Long> feedPostIds(FeedQuery q) {
        var sql = new StringBuilder("SELECT id FROM posts WHERE visibility = 'PUBLIC' AND deleted_at IS NULL AND id < :before");
        var params = new LinkedHashMap<String, Object>();
        params.put("before", q.beforeId() == null ? Long.MAX_VALUE : q.beforeId());
        if (q.category() != null) {
            sql.append(" AND category = :cat");
            params.put("cat", q.category());
        }
        if (q.nearCell() != null) {
            String cell = Geohash.normalize(q.nearCell());
            if (cell == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Lokasi tidak valid.");
            }
            sql.append(" AND geohash IN (:cells)");
            params.put("cells", Geohash.withNeighbors(cell));
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", Math.clamp(q.limit(), 1, 200));
        var spec = db.jdbc().sql(sql.toString());
        for (var e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(Long.class).list();
    }

    @Override
    public List<Long> authorOnlyPostIds(FeedQuery q, long viewerUserId) {
        var sql = new StringBuilder("""
                SELECT id FROM posts WHERE author_id = :viewer AND visibility = 'AUTHOR_ONLY' AND deleted_at IS NULL
                  AND id < :before""");
        var params = new LinkedHashMap<String, Object>();
        params.put("viewer", viewerUserId);
        params.put("before", q.beforeId() == null ? Long.MAX_VALUE : q.beforeId());
        if (q.category() != null) {
            sql.append(" AND category = :cat");
            params.put("cat", q.category());
        }
        if (q.nearCell() != null) {
            String cell = Geohash.normalize(q.nearCell());
            if (cell == null) {
                return List.of();
            }
            sql.append(" AND geohash IN (:cells)");
            params.put("cells", Geohash.withNeighbors(cell));
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", Math.clamp(q.limit(), 1, 200));
        var spec = db.jdbc().sql(sql.toString());
        for (var e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(Long.class).list();
    }

    @Override
    public List<HotCandidate> hotCandidates(Instant since, int limit) {
        return db.jdbc().sql("""
                        SELECT id, hot_score FROM posts
                        WHERE visibility = 'PUBLIC' AND deleted_at IS NULL AND created_at >= :since
                        ORDER BY hot_score DESC, id DESC LIMIT :limit""")
                .param("since", Timestamp.from(since)).param("limit", limit)
                .query((rs, n) -> new HotCandidate(rs.getLong("id"), rs.getDouble("hot_score")))
                .list();
    }

    @Override
    public List<PostView> renderPosts(List<Long> postIds, long viewerUserId) {
        return render(postIds, viewerUserId);
    }

    // ---- render --------------------------------------------------------------------------------

    private List<PostView> render(List<Long> postIds, long viewerId) {
        if (postIds.isEmpty()) {
            return List.of();
        }
        var ids = List.copyOf(new HashSet<>(postIds));
        var rows = new HashMap<Long, PostRow>();
        db.jdbc().sql("""
                        SELECT id, author_id, body, bg_media_id, category, city, heart_count, reply_count, created_at
                        FROM posts WHERE id IN (:ids) AND\s""" + VISIBLE_TO_VIEWER)
                .param("ids", ids).param("viewer", viewerId)
                .query((rs, n) -> new PostRow(rs.getLong("id"), rs.getLong("author_id"), rs.getString("body"),
                        rs.getLong("bg_media_id"), rs.getString("category"), rs.getString("city"),
                        rs.getInt("heart_count"), rs.getInt("reply_count"),
                        rs.getTimestamp("created_at").toInstant()))
                .list().forEach(r -> rows.put(r.id(), r));
        if (rows.isEmpty()) {
            return List.of();
        }
        var blocked = safety.blockedEitherWay(viewerId,
                rows.values().stream().map(PostRow::authorId).filter(a -> a != viewerId).distinct().toList());
        if (!blocked.isEmpty()) {
            rows.values().removeIf(r -> blocked.contains(r.authorId()));
            if (rows.isEmpty()) {
                return List.of();
            }
        }
        var hearted = new HashSet<>(db.jdbc().sql("SELECT post_id FROM hearts WHERE user_id = :u AND post_id IN (:ids)")
                .param("u", viewerId).param("ids", List.copyOf(rows.keySet())).query(Long.class).list());
        Map<Long, PublicIdentity> authors = identity.resolveIdentities(
                rows.values().stream().map(PostRow::authorId).distinct().toList());
        Map<Long, Background> bgs = media.findBackgrounds(
                rows.values().stream().map(PostRow::bgMediaId).distinct().toList());

        var out = new ArrayList<PostView>(rows.size());
        for (long id : postIds) {
            PostRow r = rows.get(id);
            if (r == null) {
                continue;
            }
            boolean mine = r.authorId() == viewerId;
            Background bg = bgs.get(r.bgMediaId());
            out.add(new PostView(Tsid.toString(r.id()), author(authors.get(r.authorId())), r.body(), r.category(),
                    bg == null ? null : new PostView.BackgroundRef(bg.url(), bg.blurhash()), r.city(),
                    r.heartCount(), r.replyCount(), hearted.contains(r.id()), mine, !mine, minute(r.createdAt()), null));
        }
        return out;
    }

    static PostView.Author author(PublicIdentity p) {
        return p == null ? new PostView.Author(null, DELETED_NAME, null, null)
                : new PostView.Author(p.publicId(), p.name(), p.tag(), p.color());
    }

    /** functional spec §4: {@code created_at} dibulatkan ke menit. */
    static Instant minute(Instant t) {
        return t.truncatedTo(ChronoUnit.MINUTES);
    }

    static String excerpt(String body) {
        if (body.codePointCount(0, body.length()) <= 80) {
            return body;
        }
        return body.substring(0, body.offsetByCodePoints(0, 79)) + "…";
    }
}
