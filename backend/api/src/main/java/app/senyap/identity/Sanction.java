package app.senyap.identity;

import java.time.Instant;

/**
 * Sanksi akun dari moderator atau sistem (functional spec FS-09).
 *
 * @param status {@code ACTIVE} (cabut sanksi), {@code RESTRICTED} (shadow), {@code SUSPENDED} (tidak bisa
 *               menulis), {@code BANNED} (tidak bisa login)
 * @param until  akhir sanksi; {@code null} = sampai dicabut. Diabaikan untuk {@code BANNED}/{@code ACTIVE}.
 */
public record Sanction(AccountStatus status, Instant until) {
}
