/**
 * Modul media. Upload, presign, worker gambar. Schema `media`, bucket `media`.
 *
 * <p>API publik modul (interface {@code *Api} + DTO record) ditaruh langsung di package ini.
 * Implementasi, repository, dan konfigurasi ada di {@code internal} dan tidak boleh diakses modul lain
 * (aturan M3, diuji Spring Modulith + ArchUnit).
 */
@org.springframework.modulith.ApplicationModule(displayName = "media")
package app.senyap.media;
