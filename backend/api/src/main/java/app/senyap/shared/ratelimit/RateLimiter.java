package app.senyap.shared.ratelimit;

import app.senyap.shared.error.ApiException;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Rate limit fixed-window di Redis (functional spec §6). Kunci tidak boleh berisi data pribadi mentah:
 * pakai hash (misalnya {@code phone_hmac}, HMAC alamat IP) atau ID internal.
 */
@Component
public class RateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> HIT = RedisScript.of("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); ttl = tonumber(ARGV[1]) end
            return {c, ttl}""", List.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Menghitung satu aksi pada {@code key}. Melempar {@code RATE_LIMITED} (dengan {@code Retry-After})
     * bila sudah lebih dari {@code limit} dalam {@code window}.
     */
    public void hit(String key, int limit, Duration window) {
        List<?> r = redis.execute(HIT, List.of("rl:" + key), Long.toString(window.toMillis()));
        long count = ((Number) r.get(0)).longValue();
        long ttlMs = ((Number) r.get(1)).longValue();
        if (count > limit) {
            throw ApiException.rateLimited(Duration.ofMillis(Math.max(ttlMs, 1000)));
        }
    }
}
