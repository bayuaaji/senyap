-- Schema chat. Nama tabel tidak diberi prefix schema: Flyway menjalankan migrasi ini dengan
-- search_path = chat dan user senyap_chat. Referensi ke tabel modul lain hanya berupa ID tanpa FK (aturan M2).

CREATE TABLE conversations (
    id              BIGINT PRIMARY KEY,
    origin_type     VARCHAR(10) NOT NULL CHECK (origin_type IN ('POST', 'REPLY')),
    origin_id       BIGINT      NOT NULL,          -- → posts.posts / posts.replies (logis)
    initiator_id    BIGINT      NOT NULL,          -- → identity.users (logis)
    recipient_id    BIGINT      NOT NULL,          -- → identity.users (logis)
    status          VARCHAR(10) NOT NULL DEFAULT 'REQUESTED'
                    CHECK (status IN ('REQUESTED', 'ACTIVE', 'CLOSED', 'DECLINED')),
    last_seq        BIGINT      NOT NULL DEFAULT 0,
    last_message_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT conversations_origin_uq UNIQUE (origin_type, origin_id, initiator_id),
    CONSTRAINT conversations_not_self CHECK (initiator_id <> recipient_id)
);

CREATE TABLE conversation_members (
    conversation_id    BIGINT  NOT NULL REFERENCES conversations (id),
    user_id            BIGINT  NOT NULL,           -- → identity.users (logis)
    last_delivered_seq BIGINT  NOT NULL DEFAULT 0,
    last_read_seq      BIGINT  NOT NULL DEFAULT 0,
    muted              BOOLEAN NOT NULL DEFAULT false,
    left_at            TIMESTAMPTZ,
    hidden_at          TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, user_id)
);
-- Inbox: percakapan milik user, diurutkan lewat conversations.last_message_at.
CREATE INDEX conversation_members_user_idx ON conversation_members (user_id) WHERE hidden_at IS NULL;

-- Dipartisi per bulan (created_at) supaya retensi dan purge murah (DROP partisi).
-- Postgres mewajibkan kunci partisi ada di setiap constraint unik, jadi PK = (conversation_id, seq, created_at).
-- Keunikan (conversation_id, seq) dijamin alokasi atomik conversations.last_seq; keunikan client_msg_id
-- dijamin tabel message_dedup di bawah.
CREATE TABLE messages (
    conversation_id    BIGINT      NOT NULL REFERENCES conversations (id),
    seq                BIGINT      NOT NULL,
    sender_id          BIGINT      NOT NULL,       -- → identity.users (logis). Tidak dikirim ke client.
    client_msg_id      UUID        NOT NULL,
    type               VARCHAR(10) NOT NULL CHECK (type IN ('TEXT', 'IMAGE', 'SYSTEM')),
    body               TEXT CHECK (char_length(body) <= 2000), -- plaintext; enkripsi hanya di jalur jaringan (TLS)
    media_id           BIGINT,                     -- → media.media (logis), untuk IMAGE
    deleted_for_all_at TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conversation_id, seq, created_at)
) PARTITION BY RANGE (created_at);

-- Idempotensi kirim ulang: satu client_msg_id per (conversation_id, sender_id). Tidak dipartisi.
CREATE TABLE message_dedup (
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id),
    sender_id       BIGINT      NOT NULL,
    client_msg_id   UUID        NOT NULL,
    seq             BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conversation_id, sender_id, client_msg_id)
);

-- Membuat partisi bulanan messages_YYYY_MM (UTC) dari bulan from_month sampai months_ahead bulan ke depan.
-- Mengembalikan jumlah partisi baru. Dipanggil di sini dan berkala oleh job worker.
CREATE FUNCTION ensure_message_partitions(from_month DATE, months_ahead INT) RETURNS INT
LANGUAGE plpgsql AS $$
DECLARE
    m       DATE;
    lo      TIMESTAMPTZ;
    hi      TIMESTAMPTZ;
    part    TEXT;
    created INT := 0;
BEGIN
    FOR i IN 0..months_ahead LOOP
        m    := (date_trunc('month', from_month) + make_interval(months => i))::date;
        lo   := m::timestamp AT TIME ZONE 'UTC';
        hi   := (m + interval '1 month')::timestamp AT TIME ZONE 'UTC';
        part := format('messages_%s', to_char(m, 'YYYY_MM'));
        IF to_regclass(format('%I.%I', current_schema(), part)) IS NULL THEN
            EXECUTE format('CREATE TABLE %I PARTITION OF messages FOR VALUES FROM (%L) TO (%L)', part, lo, hi);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

SELECT ensure_message_partitions(current_date, 3);

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
