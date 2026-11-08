-- Login ulang sebelum purge membatalkan penghapusan akun (keputusan 8 Nov 2026).
-- Status sebelum DELETE /v1/me disimpan supaya pembatalan tidak menghapus pembatasan moderasi
-- (RESTRICTED/SUSPENDED tidak bisa "dicuci" dengan hapus akun lalu login lagi).
ALTER TABLE users ADD COLUMN deleted_from_status VARCHAR(12)
    CHECK (deleted_from_status IN ('ACTIVE', 'RESTRICTED', 'SUSPENDED'));
