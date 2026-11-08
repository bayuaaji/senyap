package app.senyap.shared.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Menjalankan relay semua modul secara berkala. Hanya aktif di profil {@code worker}. */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final List<OutboxRelay> relays;

    OutboxRelayScheduler(List<OutboxRelay> relays) {
        this.relays = relays;
    }

    @Scheduled(fixedDelayString = "${senyap.outbox.poll-interval:500ms}")
    void relay() {
        for (OutboxRelay relay : relays) {
            try {
                relay.relayOnce();
            } catch (RuntimeException e) {
                log.warn("Relay outbox {} error: {}", relay.schema(), e.getClass().getSimpleName());
            }
        }
    }
}
