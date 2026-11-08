-- Schema notifications. Nama tabel tidak diberi prefix schema: Flyway menjalankan migrasi ini dengan
-- search_path = notifications dan user senyap_notifications. Referensi ke tabel modul lain hanya berupa ID tanpa FK (aturan M2).

CREATE TABLE notifications (
    id           BIGINT PRIMARY KEY,
    recipient_id BIGINT      NOT NULL,             -- → identity.users (logis)
    type         VARCHAR(40) NOT NULL,
    thread_type  VARCHAR(20),
    thread_id    BIGINT,
    data         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notifications_recipient_idx ON notifications (recipient_id, id DESC);
CREATE INDEX notifications_unread_idx ON notifications (recipient_id) WHERE read_at IS NULL;

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
