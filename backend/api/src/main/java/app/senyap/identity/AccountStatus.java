package app.senyap.identity;

/** Status akun (functional spec §2, tabel users). */
public enum AccountStatus {
    ACTIVE, RESTRICTED, SUSPENDED, BANNED, DELETED;

    /** Boleh memakai sesi (access/refresh token). */
    public boolean canUseSession() {
        return this != BANNED && this != DELETED;
    }
}
