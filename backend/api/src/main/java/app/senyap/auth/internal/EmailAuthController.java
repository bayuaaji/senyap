package app.senyap.auth.internal;

import app.senyap.auth.internal.AuthController.DeviceDto;
import app.senyap.auth.internal.AuthController.TokensDto;
import app.senyap.auth.internal.AuthController.VerifyResponse;
import app.senyap.identity.AccountStatus;
import app.senyap.identity.SignInResult;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daftar/masuk dengan email + kata sandi (functional spec FS-01). Respons sukses register/login sama persis
 * bentuknya dengan {@code POST /v1/auth/verify}: {@code {tokens, is_new}}. Email tidak pernah ada di respons.
 */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class EmailAuthController {

    record RegisterRequest(@JsonProperty("email") @Size(max = 320) String email,
                           @JsonProperty("password") @Size(max = 1024) String password,
                           @JsonProperty("device") @NotNull @Valid DeviceDto device,
                           @JsonProperty("age_confirmed") boolean ageConfirmed) {
    }

    record LoginRequest(@JsonProperty("email") @Size(max = 320) String email,
                        @JsonProperty("password") @Size(max = 1024) String password,
                        @JsonProperty("device") @NotNull @Valid DeviceDto device) {
    }

    record ForgotRequest(@JsonProperty("email") @Size(max = 320) String email) {
    }

    record ResetRequest(@JsonProperty("email") @Size(max = 320) String email,
                        @JsonProperty("code") @Size(max = 6) String code,
                        @JsonProperty("new_password") @Size(max = 1024) String newPassword) {
    }

    private final EmailAuthService emails;
    private final TokenService tokens;

    EmailAuthController(EmailAuthService emails, TokenService tokens) {
        this.emails = emails;
        this.tokens = tokens;
    }

    @PostMapping("/v1/auth/email/register")
    @ResponseStatus(HttpStatus.CREATED)
    VerifyResponse register(@RequestBody @Validated RegisterRequest body, HttpServletRequest request) {
        if (!body.ageConfirmed()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kamu harus berusia 18 tahun ke atas.");
        }
        return session(emails.register(body.email(), body.password(), request.getRemoteAddr()), body.device());
    }

    @PostMapping("/v1/auth/email/login")
    VerifyResponse login(@RequestBody @Validated LoginRequest body, HttpServletRequest request) {
        return session(emails.login(body.email(), body.password(), request.getRemoteAddr()), body.device());
    }

    @PostMapping("/v1/auth/email/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgot(@RequestBody @Validated ForgotRequest body, HttpServletRequest request) {
        emails.forgot(body.email(), request.getRemoteAddr());
    }

    @PostMapping("/v1/auth/email/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@RequestBody @Validated ResetRequest body, HttpServletRequest request) {
        emails.reset(body.email(), body.code(), body.newPassword(), request.getRemoteAddr());
    }

    private VerifyResponse session(SignInResult account, DeviceDto device) {
        if (account.status() == AccountStatus.BANNED) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Akun ini diblokir karena melanggar aturan.");
        }
        var t = tokens.createSession(account.userId(), account.publicId(), device.platform(),
                AuthController.blankToNull(device.pushToken()));
        return new VerifyResponse(TokensDto.of(t), !account.onboarded());
    }
}
