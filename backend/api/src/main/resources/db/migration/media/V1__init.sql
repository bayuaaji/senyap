-- Schema media. Nama tabel tidak diberi prefix schema: Flyway menjalankan migrasi ini dengan
-- search_path = media dan user senyap_media. Referensi ke tabel modul lain hanya berupa ID tanpa FK (aturan M2).

CREATE TABLE media (
    id              BIGINT PRIMARY KEY,
    owner_id        BIGINT,                        -- → identity.users (logis). NULL = galeri latar bawaan.
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING_UPLOAD'
                    CHECK (status IN ('PENDING_UPLOAD', 'PROCESSING', 'READY', 'REJECTED')),
    original_key    VARCHAR(255) NOT NULL UNIQUE,
    mime_type       VARCHAR(50)  NOT NULL,
    size_bytes      BIGINT,
    width           INT,
    height          INT,
    variants        JSONB,
    moderation      JSONB,
    perceptual_hash BYTEA,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX media_owner_idx ON media (owner_id, created_at DESC);
CREATE INDEX media_builtin_idx ON media (created_at DESC) WHERE owner_id IS NULL;
CREATE INDEX media_phash_idx ON media (perceptual_hash) WHERE perceptual_hash IS NOT NULL;

-- Outbox modul ini (aturan M7). Ditulis di transaksi yang sama dengan perubahan data, dikirim ke Kafka oleh relay.
CREATE TABLE outbox (
    id           BIGINT PRIMARY KEY,
    event_id     UUID        NOT NULL UNIQUE,
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(100) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    envelope     JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INT         NOT NULL DEFAULT 0,
    last_error   TEXT
);
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;
