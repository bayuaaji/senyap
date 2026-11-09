-- Revisi 9 Nov 2026: daftar/masuk dengan email + kata sandi (tambahan; nomor HP + OTP tetap ada).

-- Email disimpan PLAINTEXT (dinormalisasi: trim + huruf kecil) karena dibutuhkan untuk mengirim kode reset
-- kata sandi. Trade-off ini dicatat di functional spec FS-01 dan system requirements. Email tidak pernah
-- dikirim di respons API, log, event, atau metrik. Dihapus saat akun di-purge (consumer user.purged).
CREATE TABLE email_credentials (
    user_id             BIGINT PRIMARY KEY,            -- → identity.users (logis)
    email               VARCHAR(254) NOT NULL UNIQUE,
    password_hash       VARCHAR(100) NOT NULL,         -- BCrypt
    password_changed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Kode reset 6 digit, berlaku 15 menit; hanya kode terakhir per user yang berlaku. Yang disimpan hanya HMAC kode.
CREATE TABLE password_reset_codes (
    id         BIGINT PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES email_credentials (user_id) ON DELETE CASCADE,
    code_hash  BYTEA       NOT NULL,
    attempts   SMALLINT    NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX password_reset_codes_user_idx ON password_reset_codes (user_id, created_at DESC);
CREATE INDEX password_reset_codes_created_idx ON password_reset_codes (created_at);

-- Idempotensi consumer event modul lain (user.purged dari identity).
CREATE TABLE consumed_events (
    event_id    UUID PRIMARY KEY,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
