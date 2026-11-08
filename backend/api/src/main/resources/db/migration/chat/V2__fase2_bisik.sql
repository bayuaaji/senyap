-- Fase 2 (FS-05 Bisik).

-- Kapan dan kenapa percakapan selesai. closed_reason: LEFT (keluar), BLOCKED, EXPIRED (request 7 hari),
-- DECLINED (ditolak penerima). Lawan bicara tidak pernah diberi tahu bedanya LEFT dan BLOCKED.
ALTER TABLE conversations
    ADD COLUMN closed_at     TIMESTAMPTZ,
    ADD COLUMN closed_reason VARCHAR(10) CHECK (closed_reason IN ('LEFT', 'BLOCKED', 'EXPIRED', 'DECLINED')),
    ADD COLUMN accepted_at   TIMESTAMPTZ;

-- Inbox: urut last_message_at per user (member) → join conversations di schema yang sama.
CREATE INDEX conversations_inbox_idx ON conversations (last_message_at DESC, id DESC);
-- Job kedaluwarsa request 7 hari.
CREATE INDEX conversations_requested_idx ON conversations (created_at) WHERE status = 'REQUESTED';
-- Percakapan antara dua user (ditutup saat block).
CREATE INDEX conversations_pair_idx ON conversations (initiator_id, recipient_id) WHERE status IN ('REQUESTED', 'ACTIVE');

-- Event yang sudah diproses consumer modul chat (idempotensi berdasarkan event_id).
CREATE TABLE consumed_events (
    consumer    VARCHAR(40) NOT NULL,
    event_id    UUID        NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- SEMENTARA sampai modul posts (Fase 3) mengimplementasikan validasi asal Bisik.
-- Asal Bisik buatan seed/dev (SeedOriginDirectory). Hapus tabel ini setelah PostsApi dipakai.
CREATE TABLE dev_origins (
    origin_type VARCHAR(10)  NOT NULL CHECK (origin_type IN ('POST', 'REPLY')),
    origin_id   BIGINT       NOT NULL,
    owner_id    BIGINT       NOT NULL,             -- → identity.users (logis)
    excerpt     VARCHAR(80)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (origin_type, origin_id)
);
