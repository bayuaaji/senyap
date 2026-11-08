package app.senyap.feed.internal;

import app.senyap.posts.FeedQuery;
import app.senyap.posts.PostView;
import app.senyap.posts.PostsApi;
import app.senyap.shared.web.Cursor;
import app.senyap.shared.web.CursorPage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

/**
 * Feed pull tanpa fan-out (functional spec FS-03). Semua viewer membaca index yang sama lewat
 * {@link PostsApi}; yang di-cache hanya <b>daftar ID</b> halaman pertama (bersama untuk semua viewer, 15 dtk),
 * lalu tiap halaman di-render per viewer ({@code is_mine}, {@code viewer_has_hearted}, penyaringan visibility).
 * Populer memakai sorted set Redis {@value #HOT_KEY} yang dibangun ulang worker tiap menit.
 */
@Service
class FeedService {

    static final String HOT_KEY = "feed:hot";

    private final PostsApi posts;
    private final StringRedisTemplate redis;
    private final FeedProperties props;

    FeedService(PostsApi posts, StringRedisTemplate redis, FeedProperties props) {
        this.posts = posts;
        this.redis = redis;
        this.props = props;
    }

    CursorPage<PostView> latest(long viewer, String cursor) {
        return byId("feed:latest", viewer, cursor, before -> new FeedQuery(null, null, before, limitPlusOne()));
    }

    CursorPage<PostView> category(long viewer, String category, String cursor) {
        return byId("feed:category:" + category, viewer, cursor,
                before -> new FeedQuery(category, null, before, limitPlusOne()));
    }

    /** {@code cell} sudah dinormalisasi ke geohash 5 karakter; cache per sel. */
    CursorPage<PostView> nearby(long viewer, String cell, String cursor) {
        return byId("feed:nearby:" + cell, viewer, cursor, before -> new FeedQuery(null, cell, before, limitPlusOne()));
    }

    CursorPage<PostView> popular(long viewer, String cursor) {
        int n = CursorPage.DEFAULT_LIMIT;
        if (!Boolean.TRUE.equals(redis.hasKey(HOT_KEY))) {
            rebuildPopular();
        }
        double maxScore = Double.POSITIVE_INFINITY;
        String afterMember = null;
        if (cursor != null) {
            var c = Cursor.decode(cursor);
            try {
                maxScore = Double.parseDouble(c.get("s"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("cursor", e);
            }
            afterMember = c.get("id");
        }
        var candidates = new ArrayList<ZSetOperations.TypedTuple<String>>();
        var tuples = redis.opsForZSet().reverseRangeByScoreWithScores(HOT_KEY, Double.NEGATIVE_INFINITY, maxScore,
                0, n + 1 + 100);
        if (tuples != null) {
            for (var t : tuples) {
                double s = t.getScore() == null ? 0 : t.getScore();
                // ZREVRANGEBYSCORE: skor turun, skor sama → member turun (leksikografis). Cursor = (skor, member) terakhir.
                if (afterMember != null && s == maxScore && t.getValue().compareTo(afterMember) >= 0) {
                    continue;
                }
                candidates.add(t);
                if (candidates.size() == n + 1) {
                    break;
                }
            }
        }
        boolean more = candidates.size() > n;
        var page = more ? candidates.subList(0, n) : candidates;
        String next = null;
        if (more) {
            var last = page.getLast();
            next = Cursor.of(Map.of("s", Double.toString(last.getScore()), "id", last.getValue())).encode();
        }
        var ids = page.stream().map(t -> Long.parseLong(t.getValue())).toList();
        return new CursorPage<>(posts.renderPosts(ids, viewer), next);
    }

    /** Bangun ulang {@value #HOT_KEY} dari {@code hot_score} di modul posts; tukar atomik lewat RENAME. */
    int rebuildPopular() {
        var top = posts.hotCandidates(Instant.now().minus(props.popularWindow()), props.popularSize());
        if (top.isEmpty()) {
            redis.delete(HOT_KEY);
            return 0;
        }
        String tmp = HOT_KEY + ":build:" + UUID.randomUUID();
        var tuples = new java.util.HashSet<ZSetOperations.TypedTuple<String>>();
        top.forEach(c -> tuples.add(ZSetOperations.TypedTuple.of(Long.toString(c.postId()), c.hotScore())));
        redis.opsForZSet().add(tmp, tuples);
        redis.rename(tmp, HOT_KEY);
        redis.expire(HOT_KEY, Duration.ofMinutes(10));
        return top.size();
    }

    private CursorPage<PostView> byId(String cacheKey, long viewer, String cursor, Function<Long, FeedQuery> query) {
        int n = CursorPage.DEFAULT_LIMIT;
        List<Long> ids;
        if (cursor == null) {
            ids = cachedFirstPage(cacheKey, () -> posts.feedPostIds(query.apply(null)));
        } else {
            ids = posts.feedPostIds(query.apply(Cursor.decode(cursor).getLong("id")));
        }
        boolean more = ids.size() > n;
        var page = more ? ids.subList(0, n) : ids;
        String next = more ? Cursor.of("id", Long.toString(page.getLast())).encode() : null;
        return new CursorPage<>(posts.renderPosts(page, viewer), next);
    }

    private List<Long> cachedFirstPage(String key, java.util.function.Supplier<List<Long>> loader) {
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return cached.isEmpty() ? List.of() : Arrays.stream(cached.split(",")).map(Long::parseLong).toList();
        }
        var ids = loader.get();
        redis.opsForValue().set(key, String.join(",", ids.stream().map(String::valueOf).toList()), props.firstPageTtl());
        return ids;
    }

    private static int limitPlusOne() {
        return CursorPage.DEFAULT_LIMIT + 1;
    }
}
