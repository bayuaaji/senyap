package app.senyap.shared.error;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code { "error": { "code", "message", "request_id" } }} (functional spec §4). */
public record ErrorResponse(@JsonProperty("error") Body error) {

    public record Body(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("request_id") String requestId) {
    }

    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse(new Body(code, message, requestId));
    }
}
