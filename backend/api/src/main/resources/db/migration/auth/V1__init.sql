-- Schema auth. Nama tabel tidak diberi prefix schema: Flyway menjalankan migrasi ini dengan
-- search_path = auth dan user senyap_auth. Referensi ke tabel modul lain hanya berupa ID tanpa FK (aturan M2).

CREATE TABLE devices (
    id           BIGINT PRIMARY KEY,
    user_id      BIGINT      NOT NULL,             -- → identity.users (logis)
    platform     VARCHAR(10) NOT NULL CHECK (platform IN ('ios', 'android')),
    push_token   TEXT,
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX devices_user_idx ON devices (user_id);

CREATE TABLE refresh_tokens (
    id          BIGINT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,              -- → identity.users (logis)
    device_id   BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    token_hash  BYTEA       NOT NULL UNIQUE,       -- SHA-256 token; token asli tidak disimpan
    family_id   UUID        NOT NULL,              -- rotasi: satu family per login, dicabut bersama jika reuse terdeteksi
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_device_idx ON refresh_tokens (device_id);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);

CREATE TABLE otp_attempts (
    id          BIGINT PRIMARY KEY,
    phone_hmac  BYTEA       NOT NULL,              -- HMAC-SHA256(pepper, E.164); nomor asli tidak disimpan
    channel     VARCHAR(10) NOT NULL CHECK (channel IN ('SMS', 'WHATSAPP')),
    code_hash   BYTEA,
    attempts    SMALLINT    NOT NULL DEFAULT 0,
    ip_hash     BYTEA,
    expires_at  TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX otp_attempts_phone_idx ON otp_attempts (phone_hmac, created_at DESC);

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
