package app.senyap.media.internal;

import app.senyap.media.Background;
import app.senyap.media.MediaApi;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.outbox.OutboxWriter;
import app.senyap.shared.ratelimit.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Upload latar sendiri (functional spec FS-02): presigned PUT → {@code POST /v1/media/{id}/complete} →
 * event {@code media.uploaded} → worker ({@link #process}) → {@code READY}/{@code REJECTED}.
 */
@Service
class MediaService implements MediaApi {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png");

    record UploadTicket(long mediaId, String uploadUrl, Map<String, String> headers, Instant expiresAt) {
    }

    record MediaStatus(long mediaId, String status, String url, String blurhash) {
    }

    record GalleryItem(long mediaId, String url, String blurhash) {
    }

    private final ModuleDatabase db;
    private final OutboxWriter outbox;
    private final Tsid tsid;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final MediaStorageProperties storage;
    private final MediaProperties props;
    private final ImagePipeline pipeline;
    private final RateLimiter rateLimiter;

    MediaService(@Qualifier("mediaDatabase") ModuleDatabase db, @Qualifier("mediaOutbox") OutboxWriter outbox,
                 Tsid tsid, S3Client s3, S3Presigner presigner, MediaStorageProperties storage, MediaProperties props,
                 ImagePipeline pipeline, RateLimiter rateLimiter) {
        this.db = db;
        this.outbox = outbox;
        this.tsid = tsid;
        this.s3 = s3;
        this.presigner = presigner;
        this.storage = storage;
        this.props = props;
        this.pipeline = pipeline;
        this.rateLimiter = rateLimiter;
    }

    // ---- upload --------------------------------------------------------------------------------

    UploadTicket createUpload(long userId, String mimeType, long sizeBytes) {
        if (!ALLOWED_MIME.contains(mimeType)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gambar harus berformat JPEG atau PNG.");
        }
        if (sizeBytes <= 0 || sizeBytes > props.maxUploadBytes()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Ukuran gambar maksimal " + (props.maxUploadBytes() / (1024 * 1024)) + " MB.");
        }
        boolean newAccount = Tsid.timestampOf(userId).isAfter(Instant.now().minus(Duration.ofHours(24)));
        try {
            rateLimiter.hit("media:upload:" + userId,
                    newAccount ? props.uploadsPerDayNewAccount() : props.uploadsPerDay(), Duration.ofDays(1));
        } catch (ApiException e) {
            throw ApiException.quotaExceeded("UPLOAD", "Kamu sudah mencapai batas upload gambar hari ini.",
                    e.retryAfter());
        }

        long id = tsid.next();
        String key = "uploads/" + id;
        db.jdbc().sql("""
                        INSERT INTO media (id, owner_id, status, original_key, mime_type, size_bytes)
                        VALUES (:id, :owner, 'PENDING_UPLOAD', :key, :mime, :size)""")
                .param("id", id).param("owner", userId).param("key", key).param("mime", mimeType)
                .param("size", sizeBytes)
                .update();
        var presigned = presigner.presignPutObject(p -> p
                .signatureDuration(props.uploadUrlTtl())
                .putObjectRequest(o -> o.bucket(storage.bucket()).key(key).contentType(mimeType)
                        .contentLength(sizeBytes)));
        var headers = presigned.signedHeaders().entrySet().stream()
                .filter(e -> !e.getKey().equalsIgnoreCase("host"))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> String.join(",", e.getValue())));
        return new UploadTicket(id, presigned.url().toString(), headers, presigned.expiration());
    }

    /** Client selesai PUT ke storage. Idempotent: memanggil ulang mengembalikan status saat ini. */
    MediaStatus complete(long userId, long mediaId) {
        var row = ownedRow(userId, mediaId);
        if (!"PENDING_UPLOAD".equals(row.status())) {
            return toStatus(row);
        }
        long size;
        try {
            size = s3.headObject(h -> h.bucket(storage.bucket()).key(row.originalKey())).contentLength();
        } catch (NoSuchKeyException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gambar belum selesai diunggah.");
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gambar belum selesai diunggah.");
            }
            throw e;
        }
        if (size > props.maxUploadBytes()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gambar terlalu besar.");
        }
        db.tx().executeWithoutResult(s -> {
            int n = db.jdbc().sql("""
                            UPDATE media SET status = 'PROCESSING', size_bytes = :size
                            WHERE id = :id AND status = 'PENDING_UPLOAD'""")
                    .param("id", mediaId).param("size", size).update();
            if (n == 1) {
                outbox.append("media.uploaded", Long.toString(mediaId), Map.of("media_id", mediaId));
            }
        });
        return toStatus(ownedRow(userId, mediaId));
    }

    MediaStatus status(long userId, long mediaId) {
        return toStatus(ownedRow(userId, mediaId));
    }

    // ---- worker --------------------------------------------------------------------------------

    /** Dipanggil worker untuk event {@code media.uploaded}. Idempotent: hanya memproses media PROCESSING. */
    void process(long mediaId) {
        var row = findRow(mediaId).orElse(null);
        if (row == null || !"PROCESSING".equals(row.status())) {
            return;
        }
        byte[] original;
        try {
            original = s3.getObjectAsBytes(g -> g.bucket(storage.bucket()).key(row.originalKey())).asByteArray();
        } catch (NoSuchKeyException e) {
            reject(mediaId, "original hilang");
            return;
        }
        ImagePipeline.Processed out;
        try {
            if (original.length > props.maxUploadBytes()) {
                throw new ImagePipeline.RejectedImage("terlalu besar");
            }
            out = pipeline.processUpload(original);
        } catch (ImagePipeline.RejectedImage e) {
            reject(mediaId, e.getMessage());
            deleteQuietly(row.originalKey());
            return;
        }
        String displayKey = "public/bg/" + mediaId + ".jpg";
        s3.putObject(p -> p.bucket(storage.bucket()).key(displayKey).contentType("image/jpeg")
                .cacheControl("public, max-age=31536000, immutable"), RequestBody.fromBytes(out.jpeg()));
        // Moderasi gambar otomatis belum ada (FS-08, Fase 4): dicatat SKIPPED supaya bisa diproses ulang nanti.
        db.jdbc().sql("""
                        UPDATE media SET status = 'READY', display_key = :key, width = :w, height = :h,
                               blurhash = :bh, perceptual_hash = :ph, moderation = CAST(:mod AS jsonb),
                               variants = CAST(:variants AS jsonb), processed_at = now()
                        WHERE id = :id AND status = 'PROCESSING'""")
                .param("id", mediaId).param("key", displayKey).param("w", out.width()).param("h", out.height())
                .param("bh", out.blurhash()).param("ph", out.perceptualHash())
                .param("mod", "{\"status\":\"SKIPPED\"}")
                .param("variants", "{\"display\":{\"key\":\"%s\",\"width\":%d,\"height\":%d}}"
                        .formatted(displayKey, out.width(), out.height()))
                .update();
        // Original bisa memuat EXIF/GPS: tidak disimpan setelah versi aman jadi.
        deleteQuietly(row.originalKey());
    }

    /** ID media PROCESSING yang macet (event hilang/worker mati di tengah jalan), untuk diproses ulang. */
    List<Long> staleProcessing(int limit) {
        return db.jdbc().sql("""
                        SELECT id FROM media WHERE status = 'PROCESSING' AND created_at < :cutoff
                        ORDER BY created_at LIMIT :limit""")
                .param("cutoff", java.sql.Timestamp.from(Instant.now().minus(props.staleProcessingAfter())))
                .param("limit", limit)
                .query(Long.class).list();
    }

    private void reject(long mediaId, String reason) {
        log.info("Media {} ditolak pipeline: {}", mediaId, reason);
        db.jdbc().sql("""
                        UPDATE media SET status = 'REJECTED', moderation = CAST(:mod AS jsonb), processed_at = now()
                        WHERE id = :id AND status = 'PROCESSING'""")
                .param("id", mediaId)
                .param("mod", "{\"status\":\"REJECTED\",\"reason\":\"" + reason.replace("\"", "") + "\"}")
                .update();
    }

    private void deleteQuietly(String key) {
        try {
            s3.deleteObject(d -> d.bucket(storage.bucket()).key(key));
        } catch (RuntimeException e) {
            log.warn("Gagal menghapus objek original media: {}", e.getClass().getSimpleName());
        }
    }

    // ---- galeri + API --------------------------------------------------------------------------

    /** Galeri bawaan per kategori, urut id; {@code afterId} = cursor. */
    List<GalleryItem> gallery(String category, Long afterId, int limitPlusOne) {
        return db.jdbc().sql("""
                        SELECT id, display_key, blurhash FROM media
                        WHERE owner_id IS NULL AND status = 'READY' AND category = :cat AND id > :after
                        ORDER BY id LIMIT :limit""")
                .param("cat", category).param("after", afterId == null ? 0L : afterId).param("limit", limitPlusOne)
                .query((rs, n) -> new GalleryItem(rs.getLong("id"), props.publicUrl(rs.getString("display_key")),
                        rs.getString("blurhash")))
                .list();
    }

    @Override
    public Map<Long, Background> findBackgrounds(List<Long> mediaIds) {
        if (mediaIds.isEmpty()) {
            return Map.of();
        }
        return db.jdbc().sql("""
                        SELECT id, owner_id, display_key, blurhash, width, height FROM media
                        WHERE id IN (:ids) AND status = 'READY'""")
                .param("ids", List.copyOf(Set.copyOf(mediaIds)))
                .query((rs, n) -> new Background(rs.getLong("id"), rs.getObject("owner_id", Long.class),
                        props.publicUrl(rs.getString("display_key")), rs.getString("blurhash"),
                        rs.getInt("width"), rs.getInt("height")))
                .list().stream().collect(Collectors.toUnmodifiableMap(Background::mediaId, b -> b));
    }

    // ---- baris ---------------------------------------------------------------------------------

    private record Row(long id, Long ownerId, String status, String originalKey, String displayKey, String blurhash) {
    }

    private Optional<Row> findRow(long mediaId) {
        return db.jdbc().sql("SELECT id, owner_id, status, original_key, display_key, blurhash FROM media WHERE id = :id")
                .param("id", mediaId)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getObject("owner_id", Long.class), rs.getString("status"),
                        rs.getString("original_key"), rs.getString("display_key"), rs.getString("blurhash")))
                .optional();
    }

    private Row ownedRow(long userId, long mediaId) {
        return findRow(mediaId).filter(r -> r.ownerId() != null && r.ownerId() == userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private MediaStatus toStatus(Row r) {
        boolean ready = "READY".equals(r.status());
        return new MediaStatus(r.id(), r.status(), ready ? props.publicUrl(r.displayKey()) : null,
                ready ? r.blurhash() : null);
    }
}
