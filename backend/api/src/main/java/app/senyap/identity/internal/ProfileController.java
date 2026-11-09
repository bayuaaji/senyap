package app.senyap.identity.internal;

import app.senyap.identity.Gender;
import app.senyap.identity.internal.AvatarCatalog.Avatar;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Profil sendiri + alias + gender/avatar (functional spec FS-01). Respons hanya berisi public_id + alias. */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class ProfileController {

    record AliasDto(@JsonProperty("name") String name, @JsonProperty("tag") String tag,
                    @JsonProperty("color") String color) {
    }

    record AvatarDto(@JsonProperty("id") String id, @JsonProperty("emoji") String emoji) {

        static AvatarDto of(Avatar a) {
            return a == null ? null : new AvatarDto(a.id(), a.emoji());
        }
    }

    record ProfileResponse(@JsonProperty("public_id") UUID publicId, @JsonProperty("alias") AliasDto alias,
                           @JsonProperty("alias_changeable_at") Instant aliasChangeableAt,
                           @JsonProperty("onboarded") boolean onboarded,
                           @JsonProperty("gender") String gender,
                           @JsonProperty("avatar") AvatarDto avatar) {

        static ProfileResponse of(AliasService.Profile p) {
            return new ProfileResponse(p.publicId(), new AliasDto(p.name(), p.tag(), p.color()),
                    p.aliasChangeableAt(), p.onboarded(), p.gender().code(), AvatarDto.of(p.avatar()));
        }
    }

    /** {@code animal}/{@code color} = nama field lama (katalog hewan × warna), tetap diterima. */
    record SuggestRequest(@JsonProperty("first") @JsonAlias("animal") @Size(max = 40) String first,
                          @JsonProperty("second") @JsonAlias("color") @Size(max = 20) String second) {
    }

    record SuggestResponse(@JsonProperty("name") String name, @JsonProperty("tag") String tag,
                           @JsonProperty("color") String color,
                           @JsonProperty("suggestion_token") String suggestionToken) {
    }

    record ChangeRequest(@JsonProperty("suggestion_token") @NotBlank @Size(max = 64) String suggestionToken) {
    }

    record ColorDto(@JsonProperty("name") String name, @JsonProperty("hex") String hex) {
    }

    record CatalogResponse(@JsonProperty("gender") String gender, @JsonProperty("first") List<String> first,
                           @JsonProperty("second") List<ColorDto> second,
                           @JsonProperty("avatars") List<AvatarDto> avatars) {
    }

    private static final List<AvatarDto> AVATARS = AvatarCatalog.AVATARS.stream().map(AvatarDto::of).toList();

    private static final Map<Gender, CatalogResponse> CATALOGS = java.util.Arrays.stream(Gender.values())
            .collect(Collectors.toUnmodifiableMap(g -> g, g -> {
                var w = AliasCatalog.words(g);
                return new CatalogResponse(g.code(), w.first(),
                        w.second().stream().map(c -> new ColorDto(c.name(), c.hex())).toList(), AVATARS);
            }));

    private final AliasService aliases;

    ProfileController(AliasService aliases) {
        this.aliases = aliases;
    }

    @GetMapping("/v1/me/profile")
    ProfileResponse profile(AuthenticatedUser user) {
        return ProfileResponse.of(aliases.profile(user.userId()));
    }

    /**
     * {@code PUT /v1/me/profile {gender?, avatar?}}. Field yang tidak dikirim tidak diubah; {@code avatar: null}
     * menghapus avatar. Gender boleh diganti kapan saja dan tidak mengubah alias.
     */
    @PutMapping("/v1/me/profile")
    ProfileResponse updateProfile(AuthenticatedUser user, @RequestBody(required = false) JsonNode body) {
        if (body != null && !body.isNull() && !body.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        Optional<Gender> gender = Optional.empty();
        boolean avatarChanged = false;
        Optional<Avatar> avatar = Optional.empty();
        if (body != null && body.isObject()) {
            JsonNode g = body.get("gender");
            if (g != null && !g.isNull()) {
                if (!g.isString()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Pilihan gender tidak valid.");
                }
                gender = Optional.of(Gender.fromCode(g.asString())
                        .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Pilihan gender tidak valid.")));
            }
            if (body.has("avatar")) {
                avatarChanged = true;
                JsonNode a = body.get("avatar");
                if (!a.isNull()) {
                    if (!a.isString()) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Avatar tidak ada di daftar.");
                    }
                    avatar = Optional.of(AvatarCatalog.find(a.asString())
                            .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Avatar tidak ada di daftar.")));
                }
            }
        }
        return ProfileResponse.of(aliases.updateProfile(user.userId(),
                new AliasService.ProfileChange(gender, avatarChanged, avatar)));
    }

    @PostMapping("/v1/me/alias/suggest")
    SuggestResponse suggest(AuthenticatedUser user, @RequestBody(required = false) @Validated SuggestRequest body) {
        var s = aliases.suggest(user.userId(), body == null ? null : body.first(), body == null ? null : body.second());
        return new SuggestResponse(s.name(), s.tag(), s.color(), s.token());
    }

    @PutMapping("/v1/me/alias")
    ProfileResponse change(AuthenticatedUser user, @RequestBody @Validated ChangeRequest body) {
        return ProfileResponse.of(aliases.change(user.userId(), body.suggestionToken()));
    }

    /**
     * Daftar kata untuk "pilih sendiri" (teks bebas tidak diizinkan) + daftar avatar. {@code gender} kosong =
     * gender user saat ini.
     */
    @GetMapping("/v1/aliases/catalog")
    CatalogResponse catalog(AuthenticatedUser user, @RequestParam(name = "gender", required = false) String gender) {
        Gender g = gender == null || gender.isBlank() ? aliases.gender(user.userId())
                : Gender.fromCode(gender).orElseThrow(
                        () -> new ApiException(ErrorCode.VALIDATION_FAILED, "Pilihan gender tidak valid."));
        return CATALOGS.get(g);
    }
}
