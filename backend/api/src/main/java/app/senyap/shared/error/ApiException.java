package app.senyap.shared.error;

import java.time.Duration;

/**
 * Error yang dikembalikan ke client dengan format {@code {"error": {code, message, request_id}}}.
 * Kode {@code *_QUOTA_EXCEEDED} bisa dibuat dengan {@link #quotaExceeded(String, String, Duration)}.
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final org.springframework.http.HttpStatus status;
    private final Duration retryAfter;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public ApiException(ErrorCode code, String message) {
        this(code.name(), code.status(), message, null);
    }

    private ApiException(String code, org.springframework.http.HttpStatus status, String message, Duration retryAfter) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public static ApiException rateLimited(Duration retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMITED.name(), ErrorCode.RATE_LIMITED.status(),
                ErrorCode.RATE_LIMITED.defaultMessage(), retryAfter);
    }

    /** @param quotaName misalnya {@code WHISPER} → kode {@code WHISPER_QUOTA_EXCEEDED} */
    public static ApiException quotaExceeded(String quotaName, String message, Duration retryAfter) {
        return new ApiException(quotaName + "_QUOTA_EXCEEDED", org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                message, retryAfter);
    }

    public String code() {
        return code;
    }

    public org.springframework.http.HttpStatus status() {
        return status;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
