package app.senyap.feed.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker: bangun ulang sorted set feed Populer tiap menit dari {@code hot_score} (functional spec FS-03). */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class PopularFeedJob {

    private final FeedService feed;

    PopularFeedJob(FeedService feed) {
        this.feed = feed;
    }

    @Scheduled(initialDelayString = "${senyap.feed.popular-interval:1m}", fixedDelayString = "${senyap.feed.popular-interval:1m}")
    void rebuild() {
        feed.rebuildPopular();
    }
}
