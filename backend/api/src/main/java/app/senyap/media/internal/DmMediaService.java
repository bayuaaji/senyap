package app.senyap.media.internal;

import app.senyap.media.DmImage;
import app.senyap.media.DmMediaApi;
import app.senyap.safety.ImageScreening;
import app.senyap.safety.SafetyApi;
import app.senyap.safety.ScreeningDecision;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.ratelimit.RateLimiter;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Upload + baca gambar DM. Presigner dibuat di sini (bukan bean) supaya tidak bentrok dengan bean media lain.
 * Baris {@code media.media} dengan {@code original_key} berawalan {@code dm/} hanya untuk DM.
 */
@Service
class DmMediaService implements DmMediaApi, DisposableBean {

    static final String PREFIX = "dm/";
    static final Set<String> MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/heic");
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration READ_TTL = Duration.ofHours(1);
    record Upload(long mediaId, String url, Map<String, String> headers, Instant expiresAt) {
    }

    private final ModuleDatabase db;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final MediaStorageProperties props;
    private final Tsid tsid;
    private final UploadQuota quota;
    private final ImagePipeline pipeline;
    private final SafetyApi safety;
    private final MediaService media;

    DmMediaService(@Qualifier("mediaDatabase") ModuleDatabase db, S3Client s3, MediaStorageProperties props, Tsid tsid,
                   UploadQuota quota, ImagePipeline pipeline, SafetyApi safety, MediaService media) {
        this.quota = quota;
        this.pipeline = pipeline;
        this.safety = safety;
        this.media = media;
        this.db = db;
        this.s3 = s3;
        this.props = props;
        this.tsid = tsid;
        var builder = S3Presigner.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        this.presigner = builder.build();
    }

    Upload createUpload(long ownerUserId, String mimeType, long sizeBytes) {
        if (!MIME_TYPES.contains(mimeType)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Format gambar tidak didukung.");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ukuran gambar maksimal 10 MB.");
        }
        quota.hit("media-dm:", ownerUserId);
        long id = tsid.next();
        String key = PREFIX + Tsid.toString(id);
        db.jdbc().sql("""
                        INSERT INTO media (id, owner_id, status, original_key, mime_type, size_bytes)
                        VALUES (:id, :owner, 'PENDING_UPLOAD', :key, :mime, :size)""")
                .param("id", id).param("owner", ownerUserId).param("key", key).param("mime", mimeType)
                .param("size", sizeBytes)
                .update();
        var presigned = presigner.presignPutObject(r -> r.signatureDuration(UPLOAD_TTL)
                .putObjectRequest(p -> p.bucket(props.bucket()).key(key).contentType(mimeType).contentLength(sizeBytes)));
        return new Upload(id, presigned.url().toString(), Map.of("Content-Type", mimeType),
                presigned.expiration());
    }

    /** Upload selesai: cek objek ada dan sesuai yang dijanjikan, lalu {@code READY}. Idempotent. */
    DmImage complete(long ownerUserId, long mediaId) {
        record Row(String key, String mime, long size, String status) {
        }
        var row = db.jdbc().sql("""
                        SELECT original_key, mime_type, size_bytes, status FROM media
                        WHERE id = :id AND owner_id = :owner AND original_key LIKE 'dm/%'""")
                .param("id", mediaId).param("owner", ownerUserId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)))
                .optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if ("PENDING_UPLOAD".equals(row.status())) {
            long actual;
            try {
                actual = s3.headObject(h -> h.bucket(props.bucket()).key(row.key())).contentLength();
            } catch (NoSuchKeyException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gambar belum terunggah.");
            } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
                if (e.statusCode() == 404) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gambar belum terunggah.");
                }
                throw e;
            }
            if (actual <= 0 || actual > MAX_BYTES) {
                db.jdbc().sql("UPDATE media SET status = 'REJECTED' WHERE id = :id").param("id", mediaId).update();
                throw new ApiException(ErrorCode.CONTENT_REJECTED);
            }
            // FS-08: gambar DM juga lewat hash matching + klasifikasi (sinkron; gambar ≤ 10 MB).
            ImagePipeline.Signals signals;
            try {
                signals = pipeline.inspect(s3.getObjectAsBytes(g -> g.bucket(props.bucket()).key(row.key())).asByteArray());
            } catch (ImagePipeline.RejectedImage e) {
                db.jdbc().sql("UPDATE media SET status = 'REJECTED' WHERE id = :id").param("id", mediaId).update();
                throw new ApiException(ErrorCode.CONTENT_REJECTED);
            }
            var verdict = safety.screenImage(new ImageScreening(signals.hashHex(), signals.skinRatio()));
            if (verdict.decision() == ScreeningDecision.REJECT) {
                media.rejectScreened(mediaId, ownerUserId, verdict.reason());
                throw new ApiException(ErrorCode.CONTENT_REJECTED, "Gambar ini tidak bisa dikirim.");
            }
            db.jdbc().sql("UPDATE media SET status = 'READY', size_bytes = :s WHERE id = :id AND status = 'PENDING_UPLOAD'")
                    .param("s", actual).param("id", mediaId).update();
        } else if (!"READY".equals(row.status())) {
            throw new ApiException(ErrorCode.CONTENT_REJECTED);
        }
        return findDmImages(List.of(mediaId)).get(mediaId);
    }

    @Override
    public Map<Long, DmImage> findDmImages(List<Long> mediaIds) {
        if (mediaIds.isEmpty()) {
            return Map.of();
        }
        record Row(long id, long owner, String key, String mime) {
        }
        var rows = db.jdbc().sql("""
                        SELECT id, owner_id, original_key, mime_type FROM media
                        WHERE id IN (:ids) AND status = 'READY' AND original_key LIKE 'dm/%'""")
                .param("ids", List.copyOf(mediaIds))
                .query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)))
                .list();
        return rows.stream().collect(Collectors.toUnmodifiableMap(Row::id, r -> {
            var p = presigner.presignGetObject(g -> g.signatureDuration(READ_TTL)
                    .getObjectRequest(o -> o.bucket(props.bucket()).key(r.key())));
            return new DmImage(r.id(), r.owner(), r.mime(), p.url().toString(), p.expiration());
        }));
    }

    @Override
    public void destroy() {
        presigner.close();
    }
}
