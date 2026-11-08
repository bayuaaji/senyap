-- Schema posts. Nama tabel tidak diberi prefix schema: Flyway menjalankan migrasi ini dengan
-- search_path = posts dan user senyap_posts. Referensi ke tabel modul lain hanya berupa ID tanpa FK (aturan M2).

CREATE TABLE posts (
    id           BIGINT PRIMARY KEY,               -- TSID, berurut waktu
    author_id    BIGINT       NOT NULL,            -- → identity.users (logis). Tidak dikirim ke client.
    body         VARCHAR(250) NOT NULL,
    bg_media_id  BIGINT       NOT NULL,            -- → media.media (logis)
    category     VARCHAR(30)  NOT NULL CHECK (category IN ('curhat', 'cinta', 'kerja', 'kampus', 'keluarga', 'pengakuan', 'tanya', 'lainnya')),
    geohash      CHAR(5),
    city         VARCHAR(60),
    heart_count  INT          NOT NULL DEFAULT 0,
    reply_count  INT          NOT NULL DEFAULT 0,
    hot_score    DOUBLE PRECISION NOT NULL DEFAULT 0,
    visibility   VARCHAR(12)  NOT NULL DEFAULT 'PUBLIC' CHECK (visibility IN ('PUBLIC', 'HIDDEN', 'AUTHOR_ONLY')),
    deleted_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX posts_created_idx ON posts (created_at DESC);
CREATE INDEX posts_category_created_idx ON posts (category, created_at DESC);
CREATE INDEX posts_geohash_created_idx ON posts (geohash, created_at DESC);
CREATE INDEX posts_hot_idx ON posts (hot_score DESC);
CREATE INDEX posts_author_idx ON posts (author_id, created_at DESC);

CREATE TABLE replies (
    id         BIGINT PRIMARY KEY,
    post_id    BIGINT       NOT NULL REFERENCES posts (id),
    author_id  BIGINT       NOT NULL,              -- → identity.users (logis)
    body       VARCHAR(500) NOT NULL,
    visibility VARCHAR(12)  NOT NULL DEFAULT 'PUBLIC' CHECK (visibility IN ('PUBLIC', 'HIDDEN', 'AUTHOR_ONLY')),
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX replies_post_idx ON replies (post_id, id);

CREATE TABLE hearts (
    post_id    BIGINT      NOT NULL REFERENCES posts (id),
    user_id    BIGINT      NOT NULL,               -- → identity.users (logis)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);
CREATE INDEX hearts_user_idx ON hearts (user_id, created_at DESC);

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
