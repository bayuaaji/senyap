-- Fase 3: feed pull (functional spec FS-03). ID post = TSID berurut waktu, jadi urutan + cursor memakai id
-- (setara created_at, tanpa tie). Index parsial: hanya post publik yang belum dihapus yang masuk feed.
CREATE INDEX posts_feed_latest_idx ON posts (id DESC) WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;
CREATE INDEX posts_feed_category_idx ON posts (category, id DESC) WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;
CREATE INDEX posts_feed_geohash_idx ON posts (geohash, id DESC)
    WHERE visibility = 'PUBLIC' AND deleted_at IS NULL AND geohash IS NOT NULL;
CREATE INDEX posts_author_id_idx ON posts (author_id, id DESC);

-- Balasan milik user (untuk purge).
CREATE INDEX replies_author_idx ON replies (author_id);

-- FS-04: pembuat post bisa menyembunyikan balasan di post-nya. Kolom terpisah dari `visibility` supaya
-- tidak tertukar dengan tindakan moderasi (HIDDEN oleh moderator tidak boleh dibuka pembuat post).
ALTER TABLE replies ADD COLUMN hidden_by_post_author_at TIMESTAMPTZ;
-- Akun penulis sudah di-purge (event user.purged): isi diganti "[dihapus]", alias tidak ditampilkan.
ALTER TABLE replies ADD COLUMN author_purged_at TIMESTAMPTZ;

-- Consumer event idempotent berdasarkan event_id (CLAUDE.md).
CREATE TABLE consumed_events (
    event_id    UUID PRIMARY KEY,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
