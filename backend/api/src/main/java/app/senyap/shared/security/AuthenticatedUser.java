package app.senyap.shared.security;

import java.util.UUID;

/**
 * User yang sedang login, hasil verifikasi access token + terjemahan {@code public_id} → user ID oleh
 * modul identity. Hanya untuk dipakai di server; jangan pernah diserialisasi ke respons.
 */
public record AuthenticatedUser(long userId, UUID publicId, long deviceId) {
}
