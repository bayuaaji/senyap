package app.senyap.auth.internal;

import app.senyap.identity.AccountStatus;
import app.senyap.identity.IdentityApi;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Login OTP + token (functional spec FS-01). */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class AuthController {

    record OtpRequest(@JsonProperty("phone") @NotBlank @Size(max = 32) String phone,
                      @JsonProperty("channel") @Pattern(regexp = "(?i)sms|whatsapp") String channel) {
    }

    record DeviceDto(@JsonProperty("platform") @NotNull @Pattern(regexp = "ios|android") String platform,
                     @JsonProperty("push_token") @Size(max = 4096) String pushToken) {
    }

    record VerifyRequest(@JsonProperty("phone") @NotBlank @Size(max = 32) String phone,
                         @JsonProperty("code") @NotBlank @Size(max = 6) String code,
                         @JsonProperty("device") @NotNull @Valid DeviceDto device,
                         @JsonProperty("age_confirmed") boolean ageConfirmed) {
    }

    record RefreshRequest(@JsonProperty("refresh_token") @NotBlank @Size(max = 200) String refreshToken) {
    }

    record TokensDto(@JsonProperty("access_token") String accessToken,
                     @JsonProperty("refresh_token") String refreshToken,
                     @JsonProperty("expires_in") long expiresIn) {

        static TokensDto of(TokenService.Tokens t) {
            return new TokensDto(t.accessToken(), t.refreshToken(), t.expiresInSeconds());
        }
    }

    record VerifyResponse(@JsonProperty("tokens") TokensDto tokens, @JsonProperty("is_new") boolean isNew) {
    }

    record RefreshResponse(@JsonProperty("tokens") TokensDto tokens) {
    }

    private final OtpService otp;
    private final TokenService tokens;
    private final IdentityApi identity;

    AuthController(OtpService otp, TokenService tokens, IdentityApi identity) {
        this.otp = otp;
        this.tokens = tokens;
        this.identity = identity;
    }

    @PostMapping("/v1/auth/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestOtp(@RequestBody @Validated OtpRequest body, HttpServletRequest request) {
        var channel = body.channel() == null ? OtpSender.Channel.SMS
                : OtpSender.Channel.valueOf(body.channel().toUpperCase(Locale.ROOT));
        otp.request(body.phone(), channel, request.getRemoteAddr());
    }

    @PostMapping("/v1/auth/verify")
    VerifyResponse verify(@RequestBody @Validated VerifyRequest body, HttpServletRequest request) {
        if (!body.ageConfirmed()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kamu harus berusia 18 tahun ke atas.");
        }
        byte[] phoneHmac = otp.verify(body.phone(), body.code(), request.getRemoteAddr());
        var account = identity.signIn(HexFormat.of().formatHex(phoneHmac));
        if (account.status() == AccountStatus.BANNED) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Akun ini diblokir karena melanggar aturan.");
        }
        if (account.status() == AccountStatus.DELETED) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Akun dengan nomor ini sedang dihapus. Kamu bisa mendaftar lagi setelah prosesnya selesai.");
        }
        var device = body.device();
        var t = tokens.createSession(account.userId(), account.publicId(), device.platform(), blankToNull(device.pushToken()));
        return new VerifyResponse(TokensDto.of(t), !account.onboarded());
    }

    @PostMapping("/v1/auth/refresh")
    RefreshResponse refresh(@RequestBody @Validated RefreshRequest body) {
        return new RefreshResponse(TokensDto.of(tokens.refresh(body.refreshToken())));
    }

    @PostMapping("/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(AuthenticatedUser user) {
        tokens.logout(user.userId(), user.deviceId());
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
