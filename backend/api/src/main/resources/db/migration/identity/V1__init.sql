-- Schema identity. Nama tabel tidak diberi prefix schema: Flyway menjalankan migrasi ini dengan
-- search_path = identity dan user senyap_identity. Referensi ke tabel modul lain hanya berupa ID tanpa FK (aturan M2).

CREATE TABLE users (
    id               BIGINT PRIMARY KEY,           -- TSID. Tidak pernah dikirim ke client.
    phone_hmac       BYTEA       NOT NULL UNIQUE,
    public_id        UUID        NOT NULL UNIQUE,  -- handle opaque untuk client
    alias_name       VARCHAR(40) NOT NULL,         -- {hewan} {warna}, dari daftar tetap
    alias_tag        CHAR(4)     NOT NULL CHECK (alias_tag ~ '^[0-9]{4}$'),
    alias_color      CHAR(7)     NOT NULL CHECK (alias_color ~ '^#[0-9A-Fa-f]{6}$'),
    alias_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    age_confirmed_at TIMESTAMPTZ NOT NULL,         -- 18+
    status           VARCHAR(12) NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE', 'RESTRICTED', 'SUSPENDED', 'BANNED', 'DELETED')),
    trust_score      SMALLINT    NOT NULL DEFAULT 50 CHECK (trust_score BETWEEN 0 AND 100),
    home_geohash     CHAR(5),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_alias_uq UNIQUE (alias_name, alias_tag)
);

-- Hanya untuk moderasi: alias lama tetap bisa ditelusuri.
CREATE TABLE alias_history (
    id         BIGINT PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    alias_name VARCHAR(40) NOT NULL,
    alias_tag  CHAR(4)     NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to   TIMESTAMPTZ
);
CREATE INDEX alias_history_user_idx ON alias_history (user_id, valid_from DESC);
CREATE INDEX alias_history_alias_idx ON alias_history (alias_name, alias_tag);

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
