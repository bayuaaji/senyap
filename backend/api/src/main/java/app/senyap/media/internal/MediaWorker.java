package app.senyap.media.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Worker gambar (profil worker): consumer {@code media.uploaded} dari topik {@code media.events}, plus
 * sweeper untuk media PROCESSING yang macet. Idempotent: {@link MediaService#process} hanya mengubah
 * media berstatus PROCESSING (event yang terkirim ulang tidak diproses dua kali).
 */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class MediaWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaWorker.class);

    private final MediaService media;
    private final JsonMapper json;

    MediaWorker(MediaService media, JsonMapper json) {
        this.media = media;
        this.json = json;
    }

    @KafkaListener(id = "media-worker", groupId = "senyap-media-worker", topics = "${senyap.media.topic:media.events}")
    void onEvent(String envelope) {
        JsonNode node = json.readTree(envelope);
        if (!"media.uploaded".equals(node.path("type").asString())) {
            return;
        }
        long mediaId = node.path("payload").path("media_id").asLong();
        try {
            media.process(mediaId);
        } catch (RuntimeException e) {
            // Tidak dilempar ulang: sweeper akan mencoba lagi, partisi tidak macet karena satu gambar.
            log.warn("Gagal memproses media {}: {}", mediaId, e.toString());
        }
    }

    @Scheduled(initialDelayString = "${senyap.media.sweep-interval:1m}", fixedDelayString = "${senyap.media.sweep-interval:1m}")
    void sweepStale() {
        for (long id : media.staleProcessing(50)) {
            try {
                media.process(id);
            } catch (RuntimeException e) {
                log.warn("Sweeper gagal memproses media {}: {}", id, e.toString());
            }
        }
    }
}
