package app.senyap.shared.security;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * Menerjemahkan {@code public_id} di access token menjadi user ID internal. Diimplementasikan modul
 * identity (satu-satunya modul yang boleh menerjemahkan user ID ↔ public_id). Mengembalikan kosong
 * bila akun tidak ada atau tidak boleh memakai sesi lagi ({@code DELETED}, {@code BANNED}).
 */
public interface PrincipalResolver {

    OptionalLong resolveActiveUserId(UUID publicId);
}
