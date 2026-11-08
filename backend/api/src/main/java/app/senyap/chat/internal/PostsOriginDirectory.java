package app.senyap.chat.internal;

import app.senyap.chat.OriginDirectory;
import app.senyap.posts.OriginRef;
import app.senyap.posts.OriginType;
import app.senyap.posts.PostsApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Asal Bisik dari modul posts lewat {@link PostsApi#findOrigins} (batch, aturan M3/M4). Asal yang tidak
 * bisa dibisiki (tidak ada, dihapus, disembunyikan) tidak ada di hasil, dan {@code ChatService} menjawab
 * {@code NOT_FOUND}.
 */
@Component
class PostsOriginDirectory implements OriginDirectory {

    private final PostsApi posts;

    PostsOriginDirectory(PostsApi posts) {
        this.posts = posts;
    }

    @Override
    public Map<OriginKey, OriginInfo> findOrigins(List<OriginKey> keys) {
        var refs = keys.stream().map(k -> new OriginRef(OriginType.valueOf(k.type()), k.id())).distinct().toList();
        var out = new HashMap<OriginKey, OriginInfo>();
        if (refs.isEmpty()) {
            return out;
        }
        for (var o : posts.findOrigins(refs)) {
            var key = new OriginKey(o.ref().type().name(), o.ref().id());
            out.put(key, new OriginInfo(key, o.ownerUserId(), o.excerpt()));
        }
        return out;
    }
}
