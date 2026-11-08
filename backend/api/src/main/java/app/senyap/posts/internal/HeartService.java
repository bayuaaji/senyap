package app.senyap.posts.internal;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.outbox.OutboxWriter;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Hati (functional spec FS-04): idempotent, tanpa daftar siapa yang memberi hati. Baris {@code hearts}
 * ditulis sinkron (untuk {@code viewer_has_hearted}); {@code posts.heart_count} diperbarui terbatch: tiap
 * perubahan menambah delta di hash Redis {@value #DELTA_KEY}, lalu worker mem-flush ke DB
 * ({@link #flushDeltas()}).
 */
@Service
class HeartService {

    private static final Logger log = LoggerFactory.getLogger(HeartService.class);
    // Hash tag {…}: kunci sementara RENAME harus satu slot dengan kunci asal di Redis Cluster.
    static final String DELTA_KEY = "{posts:heart_delta}";

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final PostsService posts;
    private final StringRedisTemplate redis;

    HeartService(@Qualifier("postsDatabase") ModuleDatabase db, @Qualifier("postsOutbox") OutboxWriter outbox,
                 PostsService posts, StringRedisTemplate redis) {
        this.db = db;
        this.outbox = outbox;
        this.posts = posts;
        this.redis = redis;
    }

    void heart(long userId, long postId) {
        if (!posts.visibleTo(postId, userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Boolean added = db.tx().execute(s -> {
            int n = db.jdbc().sql("INSERT INTO hearts (post_id, user_id) VALUES (:p, :u) ON CONFLICT DO NOTHING")
                    .param("p", postId).param("u", userId).update();
            if (n == 1) {
                outbox.append("post.hearted", Long.toString(postId), Map.of("post_id", postId));
            }
            return n == 1;
        });
        if (Boolean.TRUE.equals(added)) {
            redis.opsForHash().increment(DELTA_KEY, Long.toString(postId), 1);
        }
    }

    void unheart(long userId, long postId) {
        if (!posts.visibleTo(postId, userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Boolean removed = db.tx().execute(s -> {
            int n = db.jdbc().sql("DELETE FROM hearts WHERE post_id = :p AND user_id = :u")
                    .param("p", postId).param("u", userId).update();
            if (n == 1) {
                outbox.append("post.unhearted", Long.toString(postId), Map.of("post_id", postId));
            }
            return n == 1;
        });
        if (Boolean.TRUE.equals(removed)) {
            redis.opsForHash().increment(DELTA_KEY, Long.toString(postId), -1);
        }
    }

    /**
     * Pindahkan hash delta ke kunci sementara (RENAME atomik, jadi increment baru masuk hash baru), terapkan
     * ke DB dalam satu transaksi, lalu hapus. Bila DB gagal, delta dikembalikan ke hash utama.
     *
     * @return jumlah post yang counter-nya diperbarui
     */
    int flushDeltas() {
        String tmp = DELTA_KEY + ":flush:" + UUID.randomUUID();
        try {
            if (!Boolean.TRUE.equals(redis.renameIfAbsent(DELTA_KEY, tmp))) {
                return 0;
            }
        } catch (RuntimeException e) {
            return 0; // kunci belum ada (tidak ada perubahan sejak flush terakhir)
        }
        Map<Object, Object> deltas = redis.opsForHash().entries(tmp);
        try {
            db.tx().executeWithoutResult(s -> deltas.forEach((post, delta) -> {
                long d = Long.parseLong((String) delta);
                if (d != 0) {
                    db.jdbc().sql("UPDATE posts SET heart_count = GREATEST(0, heart_count + :d) WHERE id = :id")
                            .param("d", d).param("id", Long.parseLong((String) post)).update();
                }
            }));
        } catch (RuntimeException e) {
            log.warn("Flush counter hati gagal, delta dikembalikan: {}", e.toString());
            deltas.forEach((post, delta) -> redis.opsForHash().increment(DELTA_KEY, (String) post,
                    Long.parseLong((String) delta)));
        }
        redis.delete(tmp);
        return deltas.size();
    }
}
