-- Fase 4 (FS-08, FS-09): trust score dinamis + sanksi moderator berbatas waktu.

-- Sanksi RESTRICTED/SUSPENDED bisa berbatas waktu (24 jam / 7 hari). NULL = sampai dicabut moderator.
ALTER TABLE users ADD COLUMN sanction_until TIMESTAMPTZ;
CREATE INDEX users_sanction_until_idx ON users (sanction_until) WHERE sanction_until IS NOT NULL;

-- Penyesuaian trust score idempotent: kunci = sumber sinyal (mis. event_id), supaya event yang
-- dikonsumsi ulang tidak menurunkan skor dua kali.
CREATE TABLE trust_adjustments (
    adjustment_key VARCHAR(100) PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id),
    delta          SMALLINT    NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX trust_adjustments_user_idx ON trust_adjustments (user_id, created_at DESC);
