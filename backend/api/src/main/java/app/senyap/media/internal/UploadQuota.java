package app.senyap.media.internal;

import app.senyap.identity.IdentityApi;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.ratelimit.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Kuota upload gambar per hari (functional spec §6): akun baru 5, normal 30, trust rendah 0; SUSPENDED ditolak. */
@Component
class UploadQuota {

    private final IdentityApi identity;
    private final RateLimiter rateLimiter;
    private final MediaProperties props;

    UploadQuota(IdentityApi identity, RateLimiter rateLimiter, MediaProperties props) {
        this.identity = identity;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    void hit(String key, long userId) {
        var standing = identity.findStandings(List.of(userId)).get(userId);
        if (standing == null || !standing.canWrite()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Akunmu sedang ditangguhkan, jadi belum bisa mengunggah.");
        }
        int limit = switch (standing.tier(Instant.now())) {
            case NEW -> props.uploadsPerDayNewAccount();
            case NORMAL -> props.uploadsPerDay();
            case LOW_TRUST -> 0;
        };
        try {
            rateLimiter.hit(key + userId, limit, Duration.ofDays(1));
        } catch (ApiException e) {
            throw ApiException.quotaExceeded("UPLOAD", "Kamu sudah mencapai batas upload gambar hari ini.",
                    e.retryAfter());
        }
    }

}
