-- Schema safety. Nama tabel tidak diberi prefix schema: Flyway menjalankan migrasi ini dengan
-- search_path = safety dan user senyap_safety. Referensi ke tabel modul lain hanya berupa ID tanpa FK (aturan M2).

-- Block dan report merujuk konten; user ID hanya dipakai di server.
CREATE TABLE blocks (
    blocker_id      BIGINT      NOT NULL,          -- → identity.users (logis)
    blocked_id      BIGINT      NOT NULL,          -- → identity.users (logis)
    conversation_id BIGINT,                        -- → chat.conversations (logis)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id)
);
CREATE INDEX blocks_blocked_idx ON blocks (blocked_id);

CREATE TABLE reports (
    id          BIGINT PRIMARY KEY,
    reporter_id BIGINT      NOT NULL,              -- → identity.users (logis)
    target_type VARCHAR(12) NOT NULL CHECK (target_type IN ('POST', 'REPLY', 'MESSAGE', 'CONVERSATION')),
    target_id   BIGINT      NOT NULL,
    reason      VARCHAR(40) NOT NULL,
    note        VARCHAR(500),
    snapshot    JSONB       NOT NULL,              -- salinan konten saat dilaporkan
    priority    SMALLINT    NOT NULL DEFAULT 0,
    status      VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACTIONED', 'DISMISSED')),
    assigned_to VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reports_queue_idx ON reports (status, priority DESC, created_at);
CREATE INDEX reports_target_idx ON reports (target_type, target_id);

CREATE TABLE moderation_actions (
    id          BIGINT PRIMARY KEY,
    target_type VARCHAR(12) NOT NULL,
    target_id   BIGINT,
    user_id     BIGINT,                            -- → identity.users (logis)
    action      VARCHAR(30) NOT NULL,
    reason      VARCHAR(200),
    actor       VARCHAR(64) NOT NULL,              -- 'AUTO' atau ID moderator
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX moderation_actions_target_idx ON moderation_actions (target_type, target_id);
CREATE INDEX moderation_actions_user_idx ON moderation_actions (user_id, created_at DESC);

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
