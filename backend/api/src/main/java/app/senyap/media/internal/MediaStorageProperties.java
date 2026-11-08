package app.senyap.media.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code senyap.media.s3.*}: MinIO lokal, Cloudflare R2 / S3 di produksi. */
@ConfigurationProperties("senyap.media.s3")
record MediaStorageProperties(String endpoint, String region, String accessKey, String secretKey, String bucket,
                              boolean pathStyle, boolean createBucket) {
}
