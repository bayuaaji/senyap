package app.senyap.safety;

/** Aksi moderator atas kasus (FS-09). Semua tercatat di {@code moderation_actions} + audit log. */
public enum ModeratorAction {
    /** Laporan tidak terbukti; konten yang ditahan otomatis dipulihkan. */
    DISMISS,
    HIDE_CONTENT,
    RESTORE_CONTENT,
    WARN,
    RESTRICT_24H,
    RESTRICT_7D,
    SUSPEND,
    BAN
}
