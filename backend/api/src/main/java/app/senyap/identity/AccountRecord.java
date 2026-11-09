package app.senyap.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Data akun untuk penelusuran identitas atas permintaan hukum (peran {@code legal}, FS-09). Tidak pernah
 * dikirim ke client app. Nomor HP tidak disimpan; yang ada hanya {@code phone_hmac} ({@code null} untuk
 * akun yang daftar dengan email; emailnya ada di modul auth).
 */
public record AccountRecord(long userId, UUID publicId, String alias, AccountStatus status, int trustScore,
                            String phoneHmacHex, Instant createdAt, Instant sanctionUntil, List<String> aliasHistory) {
}
