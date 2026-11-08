package app.senyap.safety.internal;

import app.senyap.safety.ContentDirectory;
import app.senyap.safety.ContentRef;
import app.senyap.safety.ContentSnapshot;
import app.senyap.safety.ContentType;
import app.senyap.safety.ContentVisibility;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Routing ke {@link ContentDirectory} milik modul pemilik konten (posts, chat). Bean-nya diambil lewat
 * {@link ObjectProvider} (dicari saat dipakai), jadi safety tidak bergantung ke modul itu saat kompilasi.
 */
@Component
class ContentDirectories {

    private final ObjectProvider<ContentDirectory> provider;
    private volatile Map<ContentType, ContentDirectory> byType;

    ContentDirectories(ObjectProvider<ContentDirectory> provider) {
        this.provider = provider;
    }

    Optional<ContentSnapshot> snapshot(ContentRef ref, Long viewerUserId) {
        var dir = directories().get(ref.type());
        if (dir == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(dir.snapshot(List.of(ref), viewerUserId).get(ref));
    }

    boolean moderate(ContentRef ref, ContentVisibility visibility) {
        var dir = directories().get(ref.type());
        return dir != null && dir.moderate(ref, visibility);
    }

    private Map<ContentType, ContentDirectory> directories() {
        var m = byType;
        if (m == null) {
            m = new EnumMap<>(ContentType.class);
            for (ContentDirectory d : provider.orderedStream().toList()) {
                for (ContentType t : d.supports()) {
                    m.put(t, d);
                }
            }
            byType = m;
        }
        return m;
    }
}
