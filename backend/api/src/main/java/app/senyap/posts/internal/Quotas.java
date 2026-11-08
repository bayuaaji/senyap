package app.senyap.posts.internal;

import app.senyap.identity.AccountStanding;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.ratelimit.RateLimiter;
import java.time.Duration;
import java.time.Instant;

/**
 * Kuota harian functional spec §6 per tingkat akun. Angka "trust rendah" untuk post (3) dan balasan (20) sama
 * dengan akun baru, jadi memakai properti {@code *NewAccount}.
 */
final class Quotas {

    private Quotas() {
    }

    /** @throws ApiException {@code <NAME>_QUOTA_EXCEEDED} (429 + Retry-After) */
    static void daily(RateLimiter limiter, String action, long userId, AccountStanding standing, int normal,
                      int restricted, String quotaName, String message) {
        int limit = standing.tier(Instant.now()) == AccountStanding.Tier.NORMAL ? normal : restricted;
        try {
            limiter.hit("posts:" + action + ":" + userId, limit, Duration.ofDays(1));
        } catch (ApiException e) {
            throw ApiException.quotaExceeded(quotaName, message, e.retryAfter());
        }
    }
}
