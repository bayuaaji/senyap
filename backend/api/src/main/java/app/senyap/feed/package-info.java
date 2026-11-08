/**
 * Modul feed. FS-03: latest, popular, nearby, category. Tanpa schema sendiri: membaca lewat API/read model + cache Redis.
 *
 * <p>API publik modul (interface {@code *Api} + DTO record) ditaruh langsung di package ini.
 * Implementasi, repository, dan konfigurasi ada di {@code internal} dan tidak boleh diakses modul lain
 * (aturan M3, diuji Spring Modulith + ArchUnit).
 */
@org.springframework.modulith.ApplicationModule(displayName = "feed")
package app.senyap.feed;
