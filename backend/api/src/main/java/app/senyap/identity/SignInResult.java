package app.senyap.identity;

import java.util.UUID;

/**
 * @param created  akun baru dibuat di panggilan ini
 * @param onboarded user sudah memilih alias ("Pakai alias ini") di layar buat profil
 */
public record SignInResult(long userId, UUID publicId, AccountStatus status, boolean created, boolean onboarded) {
}
