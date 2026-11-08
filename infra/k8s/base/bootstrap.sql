-- Bootstrap role + schema per modul untuk Postgres produksi (managed). Padanan DatabaseBootstrap
-- (yang hanya aktif di local/test): aplikasi TIDAK pernah memegang kredensial admin.
--
-- Jalankan sekali per lingkungan oleh infra/DBA (atau Job k8s `db-bootstrap`), dengan akun admin:
--   psql "$ADMIN_URL" -v ON_ERROR_STOP=1 \
--     -v auth_pw="$SENYAP_DB_AUTH_PASSWORD" -v identity_pw="$SENYAP_DB_IDENTITY_PASSWORD" \
--     -v media_pw="$SENYAP_DB_MEDIA_PASSWORD" -v posts_pw="$SENYAP_DB_POSTS_PASSWORD" \
--     -v chat_pw="$SENYAP_DB_CHAT_PASSWORD" -v notifications_pw="$SENYAP_DB_NOTIFICATIONS_PASSWORD" \
--     -v safety_pw="$SENYAP_DB_SAFETY_PASSWORD" -f infra/k8s/base/bootstrap.sql   # (Kustomize: file harus di dalam folder base)
--
-- Idempotent: aman dijalankan ulang (sekaligus rotasi password). Aturan M1: satu modul = satu role = satu schema;
-- role tidak mendapat akses ke schema lain, jadi query lintas schema gagal "permission denied".
-- Flyway tiap modul (dijalankan aplikasi dengan role modul itu) membuat tabelnya sendiri.

\set ON_ERROR_STOP on

SELECT pg_advisory_lock(hashtext('senyap.db.bootstrap')) AS locked \gset

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE :"DBNAME" FROM PUBLIC;

-- Satu blok per modul. format('%I'/'%L') supaya nama + password di-quote dengan benar.
SELECT format('CREATE ROLE %I LOGIN', r) FROM (VALUES ('senyap_auth'), ('senyap_identity'), ('senyap_media'),
    ('senyap_posts'), ('senyap_chat'), ('senyap_notifications'), ('senyap_safety')) v(r)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) \gexec

ALTER ROLE senyap_auth          PASSWORD :'auth_pw';
ALTER ROLE senyap_identity      PASSWORD :'identity_pw';
ALTER ROLE senyap_media         PASSWORD :'media_pw';
ALTER ROLE senyap_posts         PASSWORD :'posts_pw';
ALTER ROLE senyap_chat          PASSWORD :'chat_pw';
ALTER ROLE senyap_notifications PASSWORD :'notifications_pw';
ALTER ROLE senyap_safety        PASSWORD :'safety_pw';

SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', s, 'senyap_' || s),
       format('ALTER ROLE %I SET search_path = %I', 'senyap_' || s, s),
       format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), 'senyap_' || s)
FROM unnest(ARRAY['auth', 'identity', 'media', 'posts', 'chat', 'notifications', 'safety']) s \gexec

-- Batas koneksi per role (cegah satu modul menghabiskan max_connections). Sesuaikan dengan ukuran pool × replika.
ALTER ROLE senyap_chat  CONNECTION LIMIT 200;
ALTER ROLE senyap_posts CONNECTION LIMIT 150;

SELECT pg_advisory_unlock(hashtext('senyap.db.bootstrap')) AS unlocked \gset
