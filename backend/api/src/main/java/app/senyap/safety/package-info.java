/**
 * Modul safety. FS-07, FS-08: report, block, moderasi, trust. Schema `safety`.
 *
 * <p>API publik modul (interface {@code *Api} + DTO record) ditaruh langsung di package ini.
 * Implementasi, repository, dan konfigurasi ada di {@code internal} dan tidak boleh diakses modul lain
 * (aturan M3, diuji Spring Modulith + ArchUnit).
 */
@org.springframework.modulith.ApplicationModule(displayName = "safety")
package app.senyap.safety;
