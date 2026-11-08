-- Fase 1 (FS-01): onboarding alias, hapus akun + purge.

-- NULL sampai user menekan "Pakai alias ini" di layar buat profil. Selama NULL, batas ganti 7 hari
-- tidak berlaku (functional spec FS-01, PUT /v1/me/alias).
ALTER TABLE users ADD COLUMN onboarded_at TIMESTAMPTZ;

-- DELETE /v1/me: status = DELETED + deleted_at. Job purge worker mengisi purged_at setelah masa tenggang
-- (phone_hmac diganti nilai acak, sehingga nomor HP tidak lagi terhubung ke akun).
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN purged_at TIMESTAMPTZ;
ALTER TABLE users ADD CONSTRAINT users_deleted_chk CHECK (status <> 'DELETED' OR deleted_at IS NOT NULL);

CREATE INDEX users_purge_idx ON users (deleted_at) WHERE status = 'DELETED' AND purged_at IS NULL;

-- Satu baris alias aktif (valid_to IS NULL) per user.
CREATE UNIQUE INDEX alias_history_current_uq ON alias_history (user_id) WHERE valid_to IS NULL;
