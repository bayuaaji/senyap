package fixture.arch.gamma;

import java.util.List;
import java.util.Map;

/** Fixture: API yang benar menurut M4 (batch List masuk, Map keluar, DTO record). */
public interface GammaApi {

    record AliasDto(String publicId, String name, String tag) {
    }

    Map<Long, AliasDto> resolveAliases(List<Long> userIds);
}
