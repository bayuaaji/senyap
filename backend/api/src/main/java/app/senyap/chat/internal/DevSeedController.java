package app.senyap.chat.internal;

import app.senyap.shared.id.Tsid;
import app.senyap.shared.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SEMENTARA, hanya profil local/test: membuat "post" seed milik pemanggil supaya Bisik bisa dicoba sebelum
 * Fase 3. {@code POST /v1/dev/origins {excerpt}} → {@code {origin_type, origin_id}}.
 */
@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class DevSeedController {

    record SeedRequest(@JsonProperty("excerpt") @NotBlank @Size(max = 80) String excerpt,
                       @JsonProperty("origin_type") @Pattern(regexp = "POST|REPLY") String originType) {
    }

    record SeedResponse(@JsonProperty("origin_type") String originType, @JsonProperty("origin_id") String originId) {
    }

    private final SeedOriginDirectory origins;
    private final Tsid tsid;

    DevSeedController(SeedOriginDirectory origins, Tsid tsid) {
        this.origins = origins;
        this.tsid = tsid;
    }

    @PostMapping("/v1/dev/origins")
    @ResponseStatus(HttpStatus.CREATED)
    SeedResponse create(AuthenticatedUser user, @RequestBody @Validated SeedRequest body) {
        String type = body.originType() == null ? "POST" : body.originType();
        long id = origins.create(type, tsid.next(), user.userId(), body.excerpt());
        return new SeedResponse(type, Tsid.toString(id));
    }
}
