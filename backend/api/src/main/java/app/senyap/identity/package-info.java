/**
 * Modul identity. Alias, encoding ID publik, penjaga anonimitas. Satu-satunya modul yang boleh menerjemahkan user_id ↔ public_id / alias. Schema `identity`.
 *
 * <p>API publik modul (interface {@code *Api} + DTO record) ditaruh langsung di package ini.
 * Implementasi, repository, dan konfigurasi ada di {@code internal} dan tidak boleh diakses modul lain
 * (aturan M3, diuji Spring Modulith + ArchUnit).
 */
@org.springframework.modulith.ApplicationModule(displayName = "identity")
package app.senyap.identity;
