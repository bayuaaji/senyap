package app.senyap.chat.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Request Bisik yang tidak dijawab 7 hari → {@code DECLINED} (functional spec FS-05 §5.1 poin 7). */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class RequestExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(RequestExpiryJob.class);

    private final ChatService chat;

    RequestExpiryJob(ChatService chat) {
        this.chat = chat;
    }

    @Scheduled(initialDelayString = "${senyap.chat.request-expiry.initial-delay:1m}",
            fixedDelayString = "${senyap.chat.request-expiry.interval:10m}")
    void expire() {
        int total = 0;
        int n;
        do {
            n = chat.expireRequests(500);
            total += n;
        } while (n == 500);
        if (total > 0) {
            log.info("{} request Bisik kedaluwarsa", total);
        }
    }
}
