-- Fase 3: latar post (functional spec FS-02).
-- category    : hanya untuk galeri latar bawaan (owner_id NULL), sama dengan kategori post.
-- display_key : objek hasil pipeline yang boleh dibaca publik (prefix `public/`); original_key tetap privat.
-- blurhash    : placeholder saat gambar dimuat di client.
-- processed_at: kapan pipeline selesai (READY/REJECTED).
ALTER TABLE media
    ADD COLUMN category     VARCHAR(30),
    ADD COLUMN display_key  VARCHAR(255),
    ADD COLUMN blurhash     VARCHAR(64),
    ADD COLUMN processed_at TIMESTAMPTZ;

CREATE INDEX media_gallery_idx ON media (category, id) WHERE owner_id IS NULL AND status = 'READY';
CREATE INDEX media_stale_idx ON media (status, created_at) WHERE status IN ('PENDING_UPLOAD', 'PROCESSING');
