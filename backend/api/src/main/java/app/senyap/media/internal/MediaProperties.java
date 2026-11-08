package app.senyap.media.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code senyap.media.*} (aturan M9). Koneksi S3 ada di {@link MediaStorageProperties}.
 *
 * @param publicBaseUrl            URL dasar objek publik (prefix {@code public/}); produksi = CDN
 * @param maxUploadBytes           batas ukuran file upload
 * @param uploadUrlTtl             umur presigned PUT URL
 * @param uploadsPerDay            kuota upload per hari, akun normal (functional spec §6)
 * @param uploadsPerDayNewAccount  kuota upload per hari, akun < 24 jam
 * @param maxDimension             sisi terpanjang hasil resize
 * @param maxPixels                tolak gambar di atas ini sebelum di-decode (bom dekompresi)
 * @param blurRadius               radius blur gambar upload (spec: 2–4 px)
 * @param overlayAlpha             kegelapan overlay gambar upload (0..1)
 * @param gallerySeed              worker mengisi galeri bawaan dengan gambar placeholder bila belum lengkap
 * @param galleryPerCategory       jumlah gambar galeri per kategori
 * @param staleProcessingAfter     media PROCESSING lebih lama dari ini diproses ulang oleh sweeper
 */
@ConfigurationProperties("senyap.media")
record MediaProperties(
        @DefaultValue("http://localhost:9000/media") String publicBaseUrl,
        @DefaultValue("10485760") long maxUploadBytes,
        @DefaultValue("15m") Duration uploadUrlTtl,
        @DefaultValue("30") int uploadsPerDay,
        @DefaultValue("5") int uploadsPerDayNewAccount,
        @DefaultValue("1080") int maxDimension,
        @DefaultValue("40000000") long maxPixels,
        @DefaultValue("3") int blurRadius,
        @DefaultValue("0.35") double overlayAlpha,
        @DefaultValue("true") boolean gallerySeed,
        @DefaultValue("300") int galleryPerCategory,
        @DefaultValue("5m") Duration staleProcessingAfter) {

    String publicUrl(String key) {
        return publicBaseUrl.endsWith("/") ? publicBaseUrl + key : publicBaseUrl + "/" + key;
    }
}
