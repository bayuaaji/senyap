package app.senyap.chat.internal;

import app.senyap.contracts.rt.RealtimeBus;
import app.senyap.contracts.ws.WsFrame;
import app.senyap.identity.IdentityApi;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mengirim frame ke sesi WebSocket user lewat Redis (build guide §1.2 langkah 6): user ID → {@code public_id}
 * (identity, di-cache karena tidak pernah berubah) → presence {@code presence:{public_id}} → {@code PUBLISH
 * rt:node:{node}}. Best effort: frame yang hilang ditutup client dengan sinkron {@code last_seq}.
 */
@Component
class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final StringRedisTemplate redis;
    private final IdentityApi identity;
    private final JsonMapper json;
    private final Cache<Long, UUID> publicIds = Caffeine.newBuilder()
            .maximumSize(200_000).expireAfterAccess(Duration.ofHours(1)).build();

    RealtimePublisher(StringRedisTemplate redis, IdentityApi identity, JsonMapper json) {
        this.redis = redis;
        this.identity = identity;
        this.json = json;
    }

    /**
     * @param frameFor frame per penerima, atau {@code null} untuk tidak mengirim apa pun ke user itu
     * @return user (di antara {@code userIds}) yang tidak punya sesi WebSocket hidup = offline
     */
    Set<Long> publish(List<Long> userIds, Function<Long, WsFrame> frameFor) {
        var ids = resolve(userIds);
        var offline = new HashSet<Long>();
        long now = System.currentTimeMillis();
        for (Long userId : userIds) {
            UUID pub = ids.get(userId);
            if (pub == null) {
                continue;
            }
            Set<String> nodes = liveNodes(pub, now);
            if (nodes.isEmpty()) {
                offline.add(userId);
                continue;
            }
            WsFrame frame = frameFor.apply(userId);
            if (frame == null) {
                continue;
            }
            String payload = json.writeValueAsString(new RealtimeBus.Delivery(pub.toString(), frame));
            for (String node : nodes) {
                try {
                    redis.convertAndSend(RealtimeBus.nodeChannel(node), payload);
                } catch (RuntimeException e) {
                    log.warn("Publish ke node {} gagal: {}", node, e.getClass().getSimpleName());
                }
            }
        }
        return offline;
    }

    private Set<String> liveNodes(UUID publicId, long now) {
        Map<Object, Object> entries = redis.opsForHash().entries(RealtimeBus.presenceKey(publicId));
        var nodes = new HashSet<String>();
        long ttl = RealtimeBus.PRESENCE_TTL.toMillis();
        for (var e : entries.entrySet()) {
            String node = RealtimeBus.nodeOf(e.getKey().toString());
            long seen;
            try {
                seen = Long.parseLong(e.getValue().toString());
            } catch (NumberFormatException ex) {
                continue;
            }
            if (node != null && now - seen <= ttl) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private Map<Long, UUID> resolve(List<Long> userIds) {
        var out = new HashMap<Long, UUID>();
        var missing = new ArrayList<Long>();
        for (Long id : userIds) {
            UUID p = publicIds.getIfPresent(id);
            if (p == null) {
                missing.add(id);
            } else {
                out.put(id, p);
            }
        }
        if (!missing.isEmpty()) {
            identity.resolveIdentities(missing).forEach((id, pi) -> {
                publicIds.put(id, pi.publicId());
                out.put(id, pi.publicId());
            });
        }
        return out;
    }
}
