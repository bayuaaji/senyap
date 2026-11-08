package fixture.arch.alpha;

import fixture.arch.alpha.internal.AlphaEntity;
import java.util.stream.Stream;

/** Fixture: API yang melanggar M4 (entity internal, Stream). */
public interface AlphaApi {

    AlphaEntity find(long id);

    Stream<Long> stream();
}
