package app.senyap.shared.db;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Akun admin Postgres yang dipakai sekali saat startup untuk membuat role + schema tiap modul.
 * Hanya aktif di lokal/test. Di produksi role dan schema dibuat oleh tim infra, dan aplikasi tidak
 * pernah memegang kredensial admin.
 */
@ConfigurationProperties("senyap.db.bootstrap")
public record DatabaseBootstrapProperties(boolean enabled, String url, String username, String password) {
}
