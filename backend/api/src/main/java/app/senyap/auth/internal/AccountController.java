package app.senyap.auth.internal;

import app.senyap.identity.IdentityApi;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Siklus hidup akun yang menyentuh sesi: hapus akun dan registrasi push token device. Ada di modul auth
 * (bukan identity) karena auth → identity; arah sebaliknya akan membuat siklus antarmodul.
 */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class AccountController {

    record DeleteRequest(@JsonProperty("confirm") String confirm) {
    }

    record DeviceRequest(@JsonProperty("push_token") @NotNull @Size(min = 1, max = 4096) String pushToken) {
    }

    private final IdentityApi identity;
    private final TokenService tokens;

    AccountController(IdentityApi identity, TokenService tokens) {
        this.identity = identity;
        this.tokens = tokens;
    }

    /**
     * {@code DELETE /v1/me {confirm: "HAPUS"}} → 202. Akun ditandai {@code DELETED} (event {@code user.deleted}),
     * semua sesi dicabut; purge oleh job worker. Urutannya sengaja identity dulu: kalau pencabutan sesi gagal,
     * akun {@code DELETED} tetap tidak bisa memakai token (ditolak saat autentikasi dan refresh).
     */
    @DeleteMapping("/v1/me")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void delete(AuthenticatedUser user, @RequestBody(required = false) DeleteRequest body) {
        if (body == null || !"HAPUS".equals(body.confirm())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ketik HAPUS untuk mengonfirmasi.");
        }
        identity.markDeleted(user.userId());
        tokens.revokeAll(user.userId());
    }

    /** Registrasi / pembaruan push token device yang sedang dipakai (FCM/APNs merotasi token). */
    @PutMapping("/v1/me/device")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void device(AuthenticatedUser user, @RequestBody @Validated DeviceRequest body) {
        if (!tokens.updatePushToken(user.userId(), user.deviceId(), body.pushToken())) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
