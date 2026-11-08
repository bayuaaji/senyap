package app.senyap.media.internal;

import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Upload gambar DM: {@code POST /v1/media/dm-uploads} → presigned PUT, client mengunggah langsung ke storage,
 * lalu {@code POST /v1/media/dm-uploads/{id}/complete}. {@code media_id} dipakai di {@code msg.send}.
 */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class DmMediaController {

    record UploadRequest(@JsonProperty("mime_type") @NotBlank String mimeType,
                         @JsonProperty("size_bytes") @NotNull @Positive Long sizeBytes) {
    }

    record UploadResponse(@JsonProperty("media_id") String mediaId, @JsonProperty("upload_url") String uploadUrl,
                          @JsonProperty("upload_method") String uploadMethod,
                          @JsonProperty("upload_headers") Map<String, String> uploadHeaders,
                          @JsonProperty("expires_at") Instant expiresAt) {
    }

    record CompleteResponse(@JsonProperty("media_id") String mediaId, @JsonProperty("status") String status) {
    }

    private final DmMediaService media;

    DmMediaController(DmMediaService media) {
        this.media = media;
    }

    @PostMapping("/v1/media/dm-uploads")
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse create(AuthenticatedUser user, @RequestBody @Validated UploadRequest body) {
        var u = media.createUpload(user.userId(), body.mimeType(), body.sizeBytes());
        return new UploadResponse(Tsid.toString(u.mediaId()), u.url(), "PUT", u.headers(), u.expiresAt());
    }

    @PostMapping("/v1/media/dm-uploads/{id}/complete")
    CompleteResponse complete(AuthenticatedUser user, @PathVariable String id) {
        long mediaId;
        try {
            mediaId = Tsid.parse(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        var img = media.complete(user.userId(), mediaId);
        return new CompleteResponse(Tsid.toString(img.mediaId()), "READY");
    }
}
