package fixture.arch.beta;

import fixture.arch.alpha.AlphaHelper;
import fixture.arch.alpha.internal.AlphaEntity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/** Fixture: melanggar M3 (akses internal + class konkret), M5 (event in-memory), M6 (tx tanpa nama). */
public class BetaService {

    private final ApplicationEventPublisher publisher;

    public BetaService(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Transactional
    public long tanpaNama(AlphaEntity e) {
        publisher.publishEvent("x");
        return e.id + AlphaHelper.help();
    }

    @Transactional("betaTx")
    public void denganNama() {
    }
}
