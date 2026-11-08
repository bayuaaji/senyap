package app.senyap.posts.internal;

import app.senyap.shared.db.ModuleDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pekerjaan latar modul posts (profil worker):
 * <ul>
 *   <li>flush counter hati terbatch dari Redis ke DB;</li>
 *   <li>hitung ulang {@code hot_score} post dalam jendela Populer (spec: tiap 1 menit):
 *       {@code log10(1 + hearts + 2·replies) + created_at_epoch / 45000};</li>
 *   <li>consumer {@code user.purged} dari {@code identity.events}: post penulis dihapus, balasan diganti
 *       "[dihapus]" tanpa alias. Idempotent lewat {@code consumed_events}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class PostsWorker {

    private static final Logger log = LoggerFactory.getLogger(PostsWorker.class);

    private final ModuleDatabase db;
    private final HeartService hearts;
    private final PostsProperties props;
    private final JsonMapper json;

    PostsWorker(@Qualifier("postsDatabase") ModuleDatabase db, HeartService hearts, PostsProperties props,
                JsonMapper json) {
        this.db = db;
        this.hearts = hearts;
        this.props = props;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${senyap.posts.counter-flush-interval:2s}")
    void flushHeartCounters() {
        hearts.flushDeltas();
    }

    @Scheduled(initialDelayString = "${senyap.posts.hot-score-interval:1m}",
            fixedDelayString = "${senyap.posts.hot-score-interval:1m}")
    void recomputeHotScores() {
        recomputeHotScoresNow();
    }

    int recomputeHotScoresNow() {
        return db.jdbc().sql("""
                        UPDATE posts SET hot_score = log(1 + heart_count + 2 * reply_count)
                                                     + extract(epoch FROM created_at) / 45000
                        WHERE created_at >= :since AND deleted_at IS NULL""")
                .param("since", Timestamp.from(Instant.now().minus(props.hotWindow())))
                .update();
    }

    @KafkaListener(id = "posts-identity-consumer", groupId = "senyap-posts",
            topics = "${senyap.identity.topic:identity.events}")
    void onIdentityEvent(String envelope) {
        JsonNode node = json.readTree(envelope);
        if (!"user.purged".equals(node.path("type").asString())) {
            return;
        }
        UUID eventId = UUID.fromString(node.path("event_id").asString());
        long userId = node.path("payload").path("user_id").asLong();
        db.tx().executeWithoutResult(s -> {
            int fresh = db.jdbc().sql("INSERT INTO consumed_events (event_id) VALUES (:e) ON CONFLICT DO NOTHING")
                    .param("e", eventId).update();
            if (fresh == 0) {
                return;
            }
            int p = db.jdbc().sql("UPDATE posts SET deleted_at = now() WHERE author_id = :u AND deleted_at IS NULL")
                    .param("u", userId).update();
            int r = db.jdbc().sql("""
                            UPDATE replies SET body = '[dihapus]', author_purged_at = now()
                            WHERE author_id = :u AND author_purged_at IS NULL""")
                    .param("u", userId).update();
            if (p + r > 0) {
                log.info("Konten akun yang di-purge dianonimkan: {} post, {} balasan", p, r);
            }
        });
    }
}
