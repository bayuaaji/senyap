package app.senyap.posts.internal;

import app.senyap.posts.PostView;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.security.AuthenticatedUser;
import app.senyap.shared.web.Cursor;
import app.senyap.shared.web.CursorPage;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Post, balasan, hati (functional spec FS-02, FS-04). Respons tidak pernah berisi user ID. */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class PostsController {

    /**
     * {@code geohash}: sel lokasi yang dihitung di device (≥ 5 karakter; server memotong ke 5). Hanya dipakai
     * bila {@code use_location = true}. Server tidak pernah menerima lat/lng.
     */
    record CreatePostRequest(@JsonProperty("body") @NotBlank @Size(max = 1000) String body,
                             @JsonProperty("category") @NotBlank String category,
                             @JsonProperty("bg_media_id") @NotBlank String bgMediaId,
                             @JsonProperty("use_location") Boolean useLocation,
                             @JsonProperty("geohash") @Size(max = 12) String geohash) {
    }

    record CreateReplyRequest(@JsonProperty("body") @NotBlank @Size(max = 2000) String body) {
    }

    private final PostsService posts;
    private final RepliesService replies;
    private final HeartService hearts;

    PostsController(PostsService posts, RepliesService replies, HeartService hearts) {
        this.posts = posts;
        this.replies = replies;
        this.hearts = hearts;
    }

    @PostMapping("/v1/posts")
    @ResponseStatus(HttpStatus.CREATED)
    PostView create(AuthenticatedUser user, @RequestBody @Validated CreatePostRequest body) {
        long bg;
        try {
            bg = Tsid.parse(body.bgMediaId());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gambar latar tidak bisa dipakai.");
        }
        return posts.create(user.userId(), new PostsService.NewPost(body.body(), body.category(), bg,
                Boolean.TRUE.equals(body.useLocation()), body.geohash()));
    }

    @GetMapping("/v1/posts/{id}")
    PostView get(AuthenticatedUser user, @PathVariable String id) {
        return posts.get(user.userId(), parseId(id));
    }

    @DeleteMapping("/v1/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthenticatedUser user, @PathVariable String id) {
        posts.delete(user.userId(), parseId(id));
    }

    @GetMapping("/v1/me/posts")
    CursorPage<PostView> mine(AuthenticatedUser user, @RequestParam(required = false) String cursor) {
        int n = CursorPage.DEFAULT_LIMIT;
        Long before = cursor == null ? null : Cursor.decode(cursor).getLong("id");
        return CursorPage.of(posts.mine(user.userId(), before, n + 1), n, last -> Cursor.of("id", last.id()));
    }

    @PutMapping("/v1/posts/{id}/heart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void heart(AuthenticatedUser user, @PathVariable String id) {
        hearts.heart(user.userId(), parseId(id));
    }

    @DeleteMapping("/v1/posts/{id}/heart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unheart(AuthenticatedUser user, @PathVariable String id) {
        hearts.unheart(user.userId(), parseId(id));
    }

    @GetMapping("/v1/posts/{id}/replies")
    CursorPage<RepliesService.ReplyView> replies(AuthenticatedUser user, @PathVariable String id,
                                                 @RequestParam(required = false) String cursor) {
        int n = RepliesService.PAGE_SIZE;
        Long after = cursor == null ? null : Cursor.decode(cursor).getLong("id");
        return CursorPage.of(replies.list(user.userId(), parseId(id), after, n + 1), n,
                last -> Cursor.of("id", last.id()));
    }

    @PostMapping("/v1/posts/{id}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    RepliesService.ReplyView reply(AuthenticatedUser user, @PathVariable String id,
                                   @RequestBody @Validated CreateReplyRequest body) {
        return replies.create(user.userId(), parseId(id), body.body());
    }

    @DeleteMapping("/v1/replies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteReply(AuthenticatedUser user, @PathVariable String id) {
        replies.delete(user.userId(), parseId(id));
    }

    @PutMapping("/v1/replies/{id}/hidden")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hideReply(AuthenticatedUser user, @PathVariable String id) {
        replies.setHidden(user.userId(), parseId(id), true);
    }

    @DeleteMapping("/v1/replies/{id}/hidden")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unhideReply(AuthenticatedUser user, @PathVariable String id) {
        replies.setHidden(user.userId(), parseId(id), false);
    }

    static long parseId(String id) {
        try {
            return Tsid.parse(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}
