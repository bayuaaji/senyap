package app.senyap.safety.internal;

import app.senyap.safety.ContentRef;
import app.senyap.safety.ContentType;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.security.AuthenticatedUser;
import app.senyap.shared.web.Cursor;
import app.senyap.shared.web.CursorPage;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Report, block, kartu bantuan (FS-07, FS-08). Block dan report merujuk konten; respons tidak pernah memuat
 * user ID, dan tidak membedakan "diblokir" dari "tidak ada".
 */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class SafetyController {

    /** Untuk {@code MESSAGE}: {@code target_id} = ID percakapan, {@code seq} = nomor pesan. */
    record ReportRequest(@JsonProperty("target_type") @NotBlank String targetType,
                         @JsonProperty("target_id") @NotBlank String targetId,
                         @JsonProperty("seq") Long seq,
                         @JsonProperty("reason") @NotBlank String reason,
                         @JsonProperty("note") @Size(max = 2000) String note) {
    }

    record BlockRequest(@JsonProperty("target_type") @NotBlank String targetType,
                        @JsonProperty("target_id") @NotBlank String targetId,
                        @JsonProperty("seq") Long seq) {
    }

    record SupportCard(@JsonProperty("title") String title, @JsonProperty("message") String message,
                       @JsonProperty("verified") boolean verified, @JsonProperty("hotlines") List<Hotline> hotlines) {
    }

    record Hotline(@JsonProperty("name") String name, @JsonProperty("contact") String contact,
                   @JsonProperty("hours") String hours) {
    }

    private final ReportService reports;
    private final BlockService blocks;
    private final SafetyProperties props;

    SafetyController(ReportService reports, BlockService blocks, SafetyProperties props) {
        this.reports = reports;
        this.blocks = blocks;
        this.props = props;
    }

    @PostMapping("/v1/reports")
    @ResponseStatus(HttpStatus.CREATED)
    ReportService.ReportView report(AuthenticatedUser user, @RequestBody @Validated ReportRequest body) {
        return reports.create(user.userId(), ref(body.targetType(), body.targetId(), body.seq()), body.reason(),
                body.note());
    }

    @PostMapping("/v1/blocks")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(AuthenticatedUser user, @RequestBody @Validated BlockRequest body) {
        blocks.blockByContent(user.userId(), ref(body.targetType(), body.targetId(), body.seq()));
    }

    @GetMapping("/v1/me/blocks")
    CursorPage<BlockService.BlockView> myBlocks(AuthenticatedUser user, @RequestParam(required = false) String cursor) {
        int n = CursorPage.DEFAULT_LIMIT;
        Long before = cursor == null ? null : Cursor.decode(cursor).getLong("id");
        return CursorPage.of(blocks.list(user.userId(), before, n + 1), n, last -> Cursor.of("id", last.id()));
    }

    @DeleteMapping("/v1/me/blocks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unblock(AuthenticatedUser user, @PathVariable String id) {
        blocks.unblock(user.userId(), parse(id));
    }

    /** Kartu bantuan risiko menyakiti diri. {@code verified = false} → client menandai "perlu dicek". */
    @GetMapping("/v1/support/self-harm")
    SupportCard supportCard() {
        var s = props.support();
        return new SupportCard(s.title(), s.message(), s.verified(),
                s.hotlines().stream().map(h -> new Hotline(h.name(), h.contact(), h.hours())).toList());
    }

    static ContentRef ref(String type, String id, Long seq) {
        ContentType t;
        try {
            t = ContentType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Jenis konten tidak dikenal.");
        }
        if (t == ContentType.MEDIA) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Jenis konten tidak dikenal.");
        }
        if (t == ContentType.MESSAGE) {
            if (seq == null || seq < 1) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "seq wajib untuk pesan.");
            }
            return new ContentRef(t, parse(id), seq);
        }
        return ContentRef.of(t, parse(id));
    }

    private static long parse(String id) {
        try {
            return Tsid.parse(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}
