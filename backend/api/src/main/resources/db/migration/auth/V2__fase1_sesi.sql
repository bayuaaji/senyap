-- Fase 1 (FS-01): tinjauan ulang kolom yang dirancang di Fase 0.

-- refresh_tokens: cabut semua sesi satu user (hapus akun, ban) + bersih-bersih token kedaluwarsa.
CREATE INDEX refresh_tokens_user_active_idx ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_tokens_expires_idx ON refresh_tokens (expires_at);
-- Tidak boleh ada dua token aktif yang menggantikan token yang sama (rotasi berlomba).
ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_replaced_by_uq UNIQUE (replaced_by);

-- devices: ditandai saat refresh terakhir; push token unik global (satu token FCM/APNs = satu device).
CREATE UNIQUE INDEX devices_push_token_uq ON devices (push_token) WHERE push_token IS NOT NULL;

-- otp_attempts: rate limit per IP + bersih-bersih baris lama (job worker menghapus > 24 jam).
-- ip_hash = HMAC(pepper, IP), bukan IP mentah.
CREATE INDEX otp_attempts_created_idx ON otp_attempts (created_at);
ALTER TABLE otp_attempts ALTER COLUMN code_hash SET NOT NULL;
ALTER TABLE otp_attempts ADD CONSTRAINT otp_attempts_attempts_chk CHECK (attempts >= 0);
