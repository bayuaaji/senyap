package app.senyap.shared.db;

/**
 * Koneksi DB satu modul, dibaca dari {@code senyap.<modul>.datasource.*} (aturan M9). User DB-nya
 * hanya punya akses ke schema modul itu (M1).
 */
public record ModuleDatabaseProperties(String url, String username, String password, Integer maxPoolSize) {

    public int poolSizeOrDefault() {
        return maxPoolSize == null ? 5 : maxPoolSize;
    }
}
