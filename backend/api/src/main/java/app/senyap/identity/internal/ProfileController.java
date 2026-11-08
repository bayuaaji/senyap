package app.senyap.identity.internal;

import app.senyap.shared.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Profil sendiri + alias (functional spec FS-01). Respons hanya berisi public_id + alias. */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class ProfileController {

    record AliasDto(@JsonProperty("name") String name, @JsonProperty("tag") String tag,
                    @JsonProperty("color") String color) {
    }

    record ProfileResponse(@JsonProperty("public_id") UUID publicId, @JsonProperty("alias") AliasDto alias,
                           @JsonProperty("alias_changeable_at") Instant aliasChangeableAt,
                           @JsonProperty("onboarded") boolean onboarded) {

        static ProfileResponse of(AliasService.Profile p) {
            return new ProfileResponse(p.publicId(), new AliasDto(p.name(), p.tag(), p.color()),
                    p.aliasChangeableAt(), p.onboarded());
        }
    }

    record SuggestRequest(@JsonProperty("animal") @Size(max = 40) String animal,
                          @JsonProperty("color") @Size(max = 20) String color) {
    }

    record SuggestResponse(@JsonProperty("name") String name, @JsonProperty("tag") String tag,
                           @JsonProperty("color") String color,
                           @JsonProperty("suggestion_token") String suggestionToken) {
    }

    record ChangeRequest(@JsonProperty("suggestion_token") @NotBlank @Size(max = 64) String suggestionToken) {
    }

    record ColorDto(@JsonProperty("name") String name, @JsonProperty("hex") String hex) {
    }

    record CatalogResponse(@JsonProperty("animals") List<String> animals, @JsonProperty("colors") List<ColorDto> colors) {
    }

    private static final CatalogResponse CATALOG = new CatalogResponse(AliasCatalog.ANIMALS,
            AliasCatalog.COLORS.stream().map(c -> new ColorDto(c.name(), c.hex())).toList());

    private final AliasService aliases;

    ProfileController(AliasService aliases) {
        this.aliases = aliases;
    }

    @GetMapping("/v1/me/profile")
    ProfileResponse profile(AuthenticatedUser user) {
        return ProfileResponse.of(aliases.profile(user.userId()));
    }

    @PostMapping("/v1/me/alias/suggest")
    SuggestResponse suggest(AuthenticatedUser user, @RequestBody(required = false) @Validated SuggestRequest body) {
        var s = aliases.suggest(user.userId(), body == null ? null : body.animal(), body == null ? null : body.color());
        return new SuggestResponse(s.name(), s.tag(), s.color(), s.token());
    }

    @PutMapping("/v1/me/alias")
    ProfileResponse change(AuthenticatedUser user, @RequestBody @Validated ChangeRequest body) {
        return ProfileResponse.of(aliases.change(user.userId(), body.suggestionToken()));
    }

    /** Daftar hewan + warna untuk "pilih sendiri" (teks bebas tidak diizinkan). */
    @GetMapping("/v1/aliases/catalog")
    CatalogResponse catalog() {
        return CATALOG;
    }
}
