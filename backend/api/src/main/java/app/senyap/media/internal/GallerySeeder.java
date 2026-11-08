package app.senyap.media.internal;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Mengisi galeri latar bawaan (owner_id NULL) dengan gambar placeholder {@link GalleryArt} sampai
 * {@code senyap.media.gallery-per-category} per kategori. Jalan sekali saat worker start, di thread
 * terpisah supaya tidak menahan startup. Idempotent lewat {@code original_key} unik, jadi aman dijalankan
 * beberapa instance sekaligus (paling buruk sebagian gambar dibuat dua kali, baris DB tetap satu).
 *
 * <p>Produksi: matikan ({@code senyap.media.gallery-seed=false}) setelah aset final berlisensi diimpor.
 */
@Component
@ConditionalOnProperty(name = {"senyap.worker.enabled", "senyap.media.gallery-seed"}, havingValue = "true",
        matchIfMissing = false)
class GallerySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GallerySeeder.class);

    private final ModuleDatabase db;
    private final Tsid tsid;
    private final S3Client s3;
    private final MediaStorageProperties storage;
    private final MediaProperties props;
    private final ImagePipeline pipeline;

    GallerySeeder(@Qualifier("mediaDatabase") ModuleDatabase db, Tsid tsid, S3Client s3,
                  MediaStorageProperties storage, MediaProperties props, ImagePipeline pipeline) {
        this.db = db;
        this.tsid = tsid;
        this.s3 = s3;
        this.storage = storage;
        this.props = props;
        this.pipeline = pipeline;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("gallery-seeder").start(() -> {
            try {
                seed();
            } catch (RuntimeException e) {
                log.warn("Seed galeri latar gagal: {}", e.toString());
            }
        });
    }

    void seed() {
        int created = 0;
        for (String category : GalleryArt.CATEGORIES) {
            var existing = new HashSet<>(db.jdbc()
                    .sql("SELECT original_key FROM media WHERE owner_id IS NULL AND category = :c")
                    .param("c", category).query(String.class).list());
            for (int n = 0; n < props.galleryPerCategory(); n++) {
                String key = "public/gallery/%s/%03d.jpg".formatted(category, n);
                if (existing.contains(key)) {
                    continue;
                }
                var out = pipeline.processBuiltin(GalleryArt.render(category, n));
                s3.putObject(p -> p.bucket(storage.bucket()).key(key).contentType("image/jpeg")
                        .cacheControl("public, max-age=31536000, immutable"), RequestBody.fromBytes(out.jpeg()));
                created += db.jdbc().sql("""
                                INSERT INTO media (id, owner_id, status, original_key, display_key, mime_type, size_bytes,
                                                   width, height, blurhash, perceptual_hash, category, moderation,
                                                   processed_at)
                                VALUES (:id, NULL, 'READY', :key, :key, 'image/jpeg', :size, :w, :h, :bh, :ph, :cat,
                                        CAST('{"status":"BUILTIN"}' AS jsonb), now())
                                ON CONFLICT (original_key) DO NOTHING""")
                        .param("id", tsid.next()).param("key", key).param("size", (long) out.jpeg().length)
                        .param("w", out.width()).param("h", out.height()).param("bh", out.blurhash())
                        .param("ph", out.perceptualHash()).param("cat", category)
                        .update();
            }
        }
        if (created > 0) {
            log.info("Galeri latar bawaan: {} gambar placeholder ditambahkan", created);
        }
    }
}
