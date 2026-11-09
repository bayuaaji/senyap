-- Revisi 9 Nov 2026 (setelah uji simulator): gender + avatar di profil, dan akun tanpa nomor HP
-- (daftar dengan email + kata sandi; kredensialnya disimpan modul auth, bukan di sini).

-- Gender menentukan katalog kata alias saat acak (functional spec §1.1). Akun lama = 'unspecified'.
-- Boleh diganti kapan saja; mengganti gender tidak mengubah alias yang sudah tersimpan.
ALTER TABLE users ADD COLUMN gender VARCHAR(12) NOT NULL DEFAULT 'unspecified'
    CHECK (gender IN ('male', 'female', 'unspecified'));

-- ID avatar dari daftar tetap di server (AvatarCatalog), bukan foto. NULL = belum memilih.
-- Tidak diberi CHECK supaya daftar bisa bertambah tanpa migrasi; validasi di aplikasi.
ALTER TABLE users ADD COLUMN avatar VARCHAR(32);

-- Akun yang daftar dengan email tidak punya nomor HP. UNIQUE tetap berlaku (NULL boleh lebih dari satu).
ALTER TABLE users ALTER COLUMN phone_hmac DROP NOT NULL;
