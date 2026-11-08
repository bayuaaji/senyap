package app.senyap.media.internal;

import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.security.AuthenticatedUser;
import app.senyap.shared.web.Cursor;
import app.senyap.shared.web.CursorPage;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Galeri latar bawaan + upload latar sendiri (functional spec FS-02). Tidak ada owner/user ID di respons. */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class MediaController {

    record UploadRequest(@JsonProperty("mime_type") @NotBlank String mimeType,
                         @JsonProperty("size_bytes") @NotNull @Positive Long sizeBytes) {
    }

    record UploadResponse(@JsonProperty("media_id") String mediaId, @JsonProperty("upload_url") String uploadUrl,
                          @JsonProperty("upload_method") String uploadMethod,
                          @JsonProperty("upload_headers") Map<String, String> uploadHeaders,
                          @JsonProperty("expires_at") Instant expiresAt) {
    }

    record BackgroundDto(@JsonProperty("url") String url, @JsonProperty("blurhash") String blurhash) {
    }

    record StatusResponse(@JsonProperty("media_id") String mediaId, @JsonProperty("status") String status,
                          @JsonProperty("background") BackgroundDto background) {

        static StatusResponse of(MediaService.MediaStatus s) {
            return new StatusResponse(Tsid.toString(s.mediaId()), s.status(),
                    s.url() == null ? null : new BackgroundDto(s.url(), s.blurhash()));
        }
    }

    record GalleryItemDto(@JsonProperty("id") String id, @JsonProperty("url") String url,
                          @JsonProperty("blurhash") String blurhash) {
    }

    private final MediaService media;

    MediaController(MediaService media) {
        this.media = media;
    }

    @GetMapping("/v1/backgrounds")
    CursorPage<GalleryItemDto> backgrounds(AuthenticatedUser user, @RequestParam String category,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        if (!GalleryArt.CATEGORIES.contains(category)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kategori tidak dikenal.");
        }
        int n = CursorPage.clampLimit(limit == null ? 30 : limit);
        Long after = cursor == null ? null : Cursor.decode(cursor).getLong("id");
        var rows = media.gallery(category, after, n + 1).stream()
                .map(g -> new GalleryItemDto(Tsid.toString(g.mediaId()), g.url(), g.blurhash()))
                .toList();
        return CursorPage.of(rows, n, last -> Cursor.of("id", last.id()));
    }

    @PostMapping("/v1/media/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse createUpload(AuthenticatedUser user, @RequestBody @Validated UploadRequest body) {
        var t = media.createUpload(user.userId(), body.mimeType(), body.sizeBytes());
        return new UploadResponse(Tsid.toString(t.mediaId()), t.uploadUrl(), "PUT", t.headers(), t.expiresAt());
    }

    @PostMapping("/v1/media/{id}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    StatusResponse complete(AuthenticatedUser user, @PathVariable String id) {
        return StatusResponse.of(media.complete(user.userId(), parseId(id)));
    }

    @GetMapping("/v1/media/{id}")
    StatusResponse status(AuthenticatedUser user, @PathVariable String id) {
        return StatusResponse.of(media.status(user.userId(), parseId(id)));
    }

    private static long parseId(String id) {
        try {
            return Tsid.parse(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}
