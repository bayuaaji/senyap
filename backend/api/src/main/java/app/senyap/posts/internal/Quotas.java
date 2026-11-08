package app.senyap.posts.internal;

import app.senyap.shared.error.ApiException;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.ratelimit.RateLimiter;
import java.time.Duration;
import java.time.Instant;

/** Kuota harian functional spec §6. Umur akun dibaca dari TSID user ID (tanpa memanggil identity). */
final class Quotas {

    private Quotas() {
    }

    static boolean isNewAccount(long userId) {
        return Tsid.timestampOf(userId).isAfter(Instant.now().minus(Duration.ofHours(24)));
    }

    /** @throws ApiException {@code <NAME>_QUOTA_EXCEEDED} (429 + Retry-After) */
    static void daily(RateLimiter limiter, String action, long userId, int normal, int newAccount, String quotaName,
                      String message) {
        try {
            limiter.hit("posts:" + action + ":" + userId, isNewAccount(userId) ? newAccount : normal, Duration.ofDays(1));
        } catch (ApiException e) {
            throw ApiException.quotaExceeded(quotaName, message, e.retryAfter());
        }
    }
}
