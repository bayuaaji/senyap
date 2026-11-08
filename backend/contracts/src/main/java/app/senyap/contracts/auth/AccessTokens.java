package app.senyap.contracts.auth;

/**
 * Kontrak access token (JWT, functional spec FS-01, aturan M11/M12). Dipakai penerbit (modul auth)
 * dan semua pemverifikasi (modul lain di api, gateway realtime, service hasil pecahan).
 *
 * <ul>
 *   <li>Algoritma {@code ES256}; public key dipublikasikan di {@link #JWKS_PATH}.</li>
 *   <li>{@code sub} = {@code public_id} akun (UUID), <b>bukan</b> user ID internal: token dipegang
 *       client, dan client tidak pernah menerima user ID. Server menerjemahkan {@code public_id} → user ID
 *       lewat modul identity.</li>
 *   <li>{@value #CLAIM_DEVICE} = ID device (string) tempat sesi dibuat; dipakai logout dan registrasi push token.</li>
 * </ul>
 */
public final class AccessTokens {

    public static final String ISSUER = "senyap";
    public static final String AUDIENCE = "senyap";
    public static final String ALGORITHM = "ES256";
    public static final String CLAIM_DEVICE = "did";
    public static final String JWKS_PATH = "/.well-known/jwks.json";

    private AccessTokens() {
    }
}
