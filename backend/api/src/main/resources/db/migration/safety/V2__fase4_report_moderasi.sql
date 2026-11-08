-- Fase 4 (FS-07, FS-08, FS-09): report dengan snapshot, antrean moderator, audit, block berID.

-- Block perlu ID publik untuk DELETE /v1/me/blocks/{id} (PK tetap pasangan akun).
ALTER TABLE blocks ADD COLUMN id BIGINT;
UPDATE blocks SET id = ('x' || substr(md5(blocker_id || ':' || blocked_id), 1, 15))::bit(60)::bigint WHERE id IS NULL;
ALTER TABLE blocks ALTER COLUMN id SET NOT NULL;
CREATE UNIQUE INDEX blocks_id_uq ON blocks (id);
CREATE INDEX blocks_blocker_list_idx ON blocks (blocker_id, id DESC);

-- Report: juga item antrean otomatis (source AUTO, reporter_id NULL). priority 0 = P0 … 2 = P2.
ALTER TABLE reports ALTER COLUMN reporter_id DROP NOT NULL;
ALTER TABLE reports DROP CONSTRAINT reports_target_type_check;
ALTER TABLE reports ADD CONSTRAINT reports_target_type_check
    CHECK (target_type IN ('POST', 'REPLY', 'MESSAGE', 'CONVERSATION', 'MEDIA'));
ALTER TABLE reports ADD COLUMN target_seq     BIGINT      NOT NULL DEFAULT 0;   -- MESSAGE: seq pesan
ALTER TABLE reports ADD COLUMN target_user_id BIGINT;                            -- → identity.users (logis)
ALTER TABLE reports ADD COLUMN source         VARCHAR(6)  NOT NULL DEFAULT 'USER' CHECK (source IN ('USER', 'AUTO'));
ALTER TABLE reports ADD COLUMN labels         JSONB       NOT NULL DEFAULT '[]';
ALTER TABLE reports ADD COLUMN resolution     VARCHAR(30);
ALTER TABLE reports ADD COLUMN resolved_by    VARCHAR(100);
ALTER TABLE reports ADD COLUMN resolved_at    TIMESTAMPTZ;
ALTER TABLE reports ADD COLUMN reporter_notified_at TIMESTAMPTZ;
ALTER TABLE reports ADD CONSTRAINT reports_priority_check CHECK (priority BETWEEN 0 AND 2);
DROP INDEX reports_queue_idx;
CREATE INDEX reports_queue_idx ON reports (status, priority, id);
DROP INDEX reports_target_idx;
CREATE INDEX reports_target_idx ON reports (target_type, target_id, target_seq);
CREATE INDEX reports_target_user_idx ON reports (target_user_id);
-- Satu pelapor = satu laporan per konten (kirim ulang mengembalikan laporan yang sama).
CREATE UNIQUE INDEX reports_reporter_target_uq ON reports (reporter_id, target_type, target_id, target_seq)
    WHERE reporter_id IS NOT NULL;

ALTER TABLE moderation_actions ADD COLUMN report_id BIGINT REFERENCES reports (id);
ALTER TABLE moderation_actions ALTER COLUMN actor TYPE VARCHAR(100);

-- Audit log konsol moderator: setiap lihat kasus, aksi, dan penelusuran identitas.
CREATE TABLE moderator_audit (
    id         BIGINT PRIMARY KEY,
    actor      VARCHAR(100) NOT NULL,
    action     VARCHAR(40)  NOT NULL,
    report_id  BIGINT,
    detail     VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX moderator_audit_id_idx ON moderator_audit (id DESC);

-- Consumer event idempotent per event_id (posts.events, chat.events, media.events, safety.events).
CREATE TABLE consumed_events (
    consumer    VARCHAR(40) NOT NULL,
    event_id    UUID        NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
