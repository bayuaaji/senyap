package app.senyap.identity.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker: cabut sanksi RESTRICTED/SUSPENDED yang sudah lewat masanya (FS-09, batasi 24 jam / 7 hari). */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class SanctionExpiryJob {

    private final IdentityService identity;

    SanctionExpiryJob(IdentityService identity) {
        this.identity = identity;
    }

    @Scheduled(initialDelayString = "${senyap.identity.sanction-sweep:1m}",
            fixedDelayString = "${senyap.identity.sanction-sweep:1m}")
    void run() {
        while (identity.expireSanctions(100) == 100) {
            // lanjut sampai habis
        }
    }
}
