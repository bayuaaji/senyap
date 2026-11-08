package app.senyap.feed.internal;

import app.senyap.posts.PostView;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.security.AuthenticatedUser;
import app.senyap.shared.web.CursorPage;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Feed Terbaru, Populer, Sekitar, Kategori (functional spec FS-03). 20 item per halaman, cursor. */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class FeedController {

    private static final List<String> CATEGORIES =
            List.of("curhat", "cinta", "kerja", "kampus", "keluarga", "pengakuan", "tanya", "lainnya");
    private static final Pattern GEOHASH = Pattern.compile("[0-9bcdefghjkmnpqrstuvwxyz]{5,12}");

    private final FeedService feed;

    FeedController(FeedService feed) {
        this.feed = feed;
    }

    @GetMapping("/v1/feed/latest")
    CursorPage<PostView> latest(AuthenticatedUser user, @RequestParam(required = false) String cursor) {
        return feed.latest(user.userId(), cursor);
    }

    @GetMapping("/v1/feed/popular")
    CursorPage<PostView> popular(AuthenticatedUser user, @RequestParam(required = false) String cursor) {
        return feed.popular(user.userId(), cursor);
    }

    /**
     * {@code cell}: geohash lokasi viewer yang dihitung di device (≥ 5 karakter, dipotong ke 5 ≈ 5 km).
     * Tanpa izin lokasi, client tidak memanggil endpoint ini.
     */
    @GetMapping("/v1/feed/nearby")
    CursorPage<PostView> nearby(AuthenticatedUser user, @RequestParam String cell,
                                @RequestParam(required = false) String cursor) {
        String g = cell.toLowerCase(Locale.ROOT);
        if (!GEOHASH.matcher(g).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Lokasi tidak valid.");
        }
        return feed.nearby(user.userId(), g.substring(0, 5), cursor);
    }

    @GetMapping("/v1/feed/category/{slug}")
    CursorPage<PostView> category(AuthenticatedUser user, @PathVariable String slug,
                                  @RequestParam(required = false) String cursor) {
        if (!CATEGORIES.contains(slug)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return feed.category(user.userId(), slug, cursor);
    }
}
