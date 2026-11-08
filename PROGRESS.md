# Progress

Diperbarui di akhir setiap sesi. Sesi baru mulai dari sini.

## Status

| Fase | Status | Catatan |
|---|---|---|
| Dokumen (requirements, spec, guide) | ✅ Selesai | 8 Nov 2026 |
| 0 Fondasi | ✅ Selesai | 8 Nov 2026, commit `bd1c959` |
| 1 Akun anonim | ✅ Selesai | 8 Nov 2026, commit `a2bfb75` |
| 2 Bisik / DM | ⏳ Berikutnya | |
| 3 Post, feed, balasan | ⬜ Belum | |
| 4 Keselamatan | ⬜ Belum | Wajib sebelum beta |
| 5 Siap produksi | ⬜ Belum | |

Checklist rinci tiap fase ada di `docs/03-build-guide.md` §4. Centang di sana saat item selesai.

## Fase yang sedang berjalan

**Fase 2 — Bisik / DM.** Belum dimulai. Fase 1 selesai (commit `a2bfb75`).

## Pertanyaan terbuka

- Nomor hotline Indonesia untuk kartu bantuan risiko menyakiti diri (cek sebelum Fase 4).
- Kepatuhan hukum (PSE, UU PDP) perlu dicek dengan konsultan sebelum rilis.
- Penyedia OTP (SMS/WhatsApp) belum dipilih. Sampai ada implementasi `OtpSender` untuk produksi, api sengaja gagal start di luar profil local/test. API moderasi teks/gambar juga belum dipilih (Fase 4).
- Login ulang dengan nomor yang akunnya sedang dihapus (sebelum purge) saat ini ditolak 403. Perlu diputuskan: tetap tolak, atau batalkan penghapusan (aktifkan lagi)?

## Catatan serah terima

Tulis entri baru di paling atas dengan format ini:

```text
### YYYY-MM-DD — Fase N
- Selesai: …
- Sedang dikerjakan / setengah jadi: … (file, apa yang kurang)
- Masalah / keputusan baru: …
- Langkah berikutnya: …
```

### 2026-11-08 — Fase 1
- Selesai: semua item Fase 1 di build guide §4. `./mvnw verify` lulus (contracts 2, api 52 unit + 25 IT, realtime 3); `flutter analyze` bersih, `flutter test` 11 lulus. Dicoba manual dengan profil local: OTP → verify → profile → JWKS.
  - **shared**: `security/` (`AccessTokenVerifier` ES256 lewat JWKS, `BearerTokenFilter` untuk `/v1/**` kecuali `/v1/auth/{otp,verify,refresh}`, `AuthenticatedUser` sebagai parameter controller, `PrincipalResolver`, `LocalJwks`), `ratelimit/RateLimiter` (Redis fixed window, Lua), `ErrorCode.OTP_INVALID`, `ApiException.withRetryAfter`.
  - **auth**: `OtpService` (5 menit, 3 percobaan, hanya kode terakhir berlaku; 3/jam per nomor, 10/jam per IP, 30 verify/jam per IP), `PhoneNumbers` (normalisasi E.164), `PhoneHasher` (HMAC pepper `senyap.auth.phone-pepper`; juga untuk IP + kode OTP), `OtpSender` + `LoggingOtpSender` (local/test, tanpa nomor/kode), `OtpCodes` (kode tetap hanya di local/test, lewat `@Profile`), `TokenService` (device + refresh family per login, rotasi `FOR UPDATE`, deteksi reuse, jeda retry 10 dtk), `SigningKeys` (JWK dari `senyap.auth.signing-jwk`, `GenerateSigningKey`), `JwksController` (`/.well-known/jwks.json`), `AuthController`, `AccountController` (`DELETE /v1/me`, `PUT /v1/me/device`), `AuthMaintenanceJob` (bersih otp_attempts > 24 jam, refresh token kedaluwarsa). Migrasi `auth/V2`.
  - **identity**: `IdentityApi` (`signIn`, `findAccounts`, `resolveIdentities`, `markDeleted`) + DTO `SignInResult`/`AccountSummary`/`PublicIdentity`/`AccountStatus`; `AliasCatalog` (200 hewan tanpa hewan-makian × 20 warna dengan hex), `AliasGenerator`, `AliasService` (suggest → token sekali pakai di Redis 10 menit → `PUT`; 7 hari kecuali onboarding; `alias_history`), `ProfileController` (+ `GET /v1/aliases/catalog`), `AccountPurgeJob` (worker). Event outbox: `user.registered`, `user.alias_changed`, `user.deleted`, `user.purged`. Migrasi `identity/V2` (`onboarded_at`, `deleted_at`, `purged_at`).
  - **realtime**: handshake `/v1/ws` wajib `Authorization: Bearer`, diverifikasi lewat JWKS api (`senyap.rt.jwks-uri`); principal (public_id + device) disalin ke atribut sesi WS.
  - **contracts**: `auth/AccessTokens` (issuer, audience, klaim `did`, path JWKS).
  - **IT**: `AccountLifecycleIT` (register → buat profil → login device lain → refresh → hapus akun → purge → daftar ulang; nomor HP tidak muncul di log), reuse refresh token, logout per device, `AuthOtpIT`, `ProfileAliasIT`. `AnonymityGuardIT` disesuaikan (`/v1/**` kini 401 tanpa token).
  - **mobile**: dio + flutter_secure_storage. `core/api` (`AuthInterceptor`: Bearer + refresh sekali untuk 401 bersamaan, `ApiError`), `core/auth` (`SecureTokenStore`, `SessionController`), router dengan redirect per status sesi; layar pembuka anonimitas, usia 18+, nomor, OTP, buat profil (`AliasEditor`: Acak lagi + pilih hewan/warna), izin, pengaturan (ganti alias, keluar, hapus akun dengan ketik HAPUS). Base URL `--dart-define=SENYAP_API_URL` (default `http://localhost:8180`).
- Sedang dikerjakan / setengah jadi: tidak ada.
- Masalah / keputusan baru (perlu diketahui sesi berikutnya):
  - **`sub` access token = `public_id`, bukan user ID** (client tidak boleh menerima user ID). Server menerjemahkan lewat `PrincipalResolver` (identity, cache Caffeine 30 dtk). Akibatnya akun yang dihapus/di-ban baru ditolak access token-nya paling lama 30 dtk kemudian di instance lain; refresh langsung ditolak.
  - **Gateway realtime hanya tahu `public_id`.** Fase 2 perlu cara routing pesan (misalnya event chat membawa `public_id` penerima, atau API internal terjemahan).
  - Verifier di api memakai JWKS penerbit di proses yang sama (`LocalJwks`); set `senyap.security.jwks-uri` bila modul dipecah. Rotasi signing key belum ada (catatan di `SigningKeys`, Fase 5).
  - `DELETE /v1/me` ada di modul **auth** (bukan identity) supaya arah ketergantungan tetap auth → identity tanpa siklus. Pencabutan sesi sinkron; event `user.deleted` untuk modul lain.
  - Purge: grace 29 hari + job tiap jam (< 30 hari). Alias + `alias_history` dipertahankan (alias tidak dilepas, mencegah penyamaran). Consumer `user.purged` → "[dihapus]" dibuat bersama posts/chat.
  - Rahasia produksi wajib: `SENYAP_AUTH_PHONE_PEPPER` (≥ 32 karakter, tidak boleh berubah setelah ada data) dan `SENYAP_AUTH_SIGNING_JWK`. Local/test: pepper dev di yml + signing key sementara (access token tidak valid setelah restart; refresh tetap jalan).
  - Rate limit per IP memakai `request.getRemoteAddr()`; di belakang load balancer perlu `server.forward-headers-strategy` (Fase 5).
  - Push token: `verify` mengirim `push_token: null` dari mobile; registrasi lewat `PUT /v1/me/device` setelah FCM/APNs disiapkan (Fase 2). Satu push token hanya milik satu device.
  - Spec diperbarui: endpoint `PUT /v1/me/device`, `GET /v1/aliases/catalog`, JWKS, kode `OTP_INVALID`, event `user.*`.
- Belum diverifikasi: `flutter run` di simulator/device (Xcode/Android SDK belum ada) — termasuk `flutter_secure_storage` di Keychain/Keystore dan apakah `minSdk` Android perlu dinaikkan; workflow GitHub Actions (belum ada remote).
- Langkah berikutnya: Fase 2 (Bisik/DM, FS-05).

### 2026-11-08 — Fase 0
- Selesai: semua item Fase 0 di build guide §4.
  - `infra/docker-compose.yml`: postgres:16, redis:7, redpanda v24.3, MinIO + `minio-init` (bucket `media`), semua dengan healthcheck.
  - `backend/` Maven multi-module (`contracts`, `api`, `realtime`) + `./mvnw` (Maven 3.10, dari start.spring.io).
    Spring Boot **4.1.2** + Spring Modulith **2.1.1** (stabil terbaru). `CLAUDE.md`, build guide, dan tech decisions sudah diperbarui dari "Boot 3".
  - `api`: 10 modul (auth, identity, media, posts, feed, chat, notifications, safety, admin, shared), virtual threads,
    profil `local`/`test`/`api`/`worker`. Tiap modul ber-schema punya role + schema + Flyway + pool + TM (`<modul>Tx`) + outbox + topik `<modul>.events` sendiri.
  - `shared`: `Tsid`, `ErrorCode`/`ApiException`/`ErrorResponse` + handler global + `RequestIdFilter`, `Cursor`/`CursorPage`, `OutboxWriter` + `OutboxRelay` (worker, `FOR UPDATE SKIP LOCKED`). Envelope event + frame WS di `contracts` (+ JSON Schema v1).
  - `realtime`: WebFlux, `/v1/ws` (Fase 0: `ping`→`pong`, frame lain → `error NOT_IMPLEMENTED`).
  - Test (semua lulus, `./mvnw verify`): 35 unit (Modulith verify, ArchUnit M3/M4/M5/M6 dengan fixture pelanggaran, aturan migrasi M1/M2/M7, penjaga anonimitas, TSID, cursor, OTP mock), 12 IT Testcontainers (health, tabel per schema, isolasi role, tanpa FK lintas schema, partisi messages, Redis, MinIO, outbox→Redpanda, format error, penjaga anonimitas menangkap kebocoran field & nilai), 2 realtime, 2 contracts.
  - Sudah dicoba manual: api + worker + realtime jalan bersamaan, ketiga health `UP`, WS ping/pong.
  - CI: `.github/workflows/backend.yml` (`./mvnw -B verify`) + `mobile.yml` (analyze + test).
  - `mobile/`: Flutter 3.47.9, go_router 18 (`StatefulShellRoute`) + Riverpod 3, 5 tab kosong. `flutter analyze` bersih, 2 widget test lulus.
- Sedang dikerjakan / setengah jadi: tidak ada. (Kemudian di-commit sebagai `bd1c959` di branch `main`.)
- Masalah / keputusan baru (perlu diketahui sesi berikutnya):
  - Image `minio/minio` (Docker Hub/quay) tidak bisa ditarik lagi → pakai `cgr.dev/chainguard/minio:latest` (hanya tag latest gratis; tidak bisa di-pin). Di test lewat `asCompatibleSubstituteFor("minio/minio")`.
  - Port 8080 dipakai app lain di laptop ini → api **8180**, worker **8182**, realtime **8190**.
  - `chat.messages`: Postgres mewajibkan kunci partisi di constraint unik, jadi PK = `(conversation_id, seq, created_at)`; idempotensi `client_msg_id` lewat tabel tak-terpartisi `chat.message_dedup` (PK `(conversation_id, sender_id, client_msg_id)` + `seq`). Fase 2: insert ke `message_dedup` dulu (ON CONFLICT → kembalikan ack lama), baru alokasi seq + insert messages, satu transaksi `chatTx`.
  - Partisi: fungsi `chat.ensure_message_partitions(date, n)`; V1 membuat bulan ini + 3 bulan; job worker `MessagePartitionMaintenance` menjaganya tiap 6 jam.
  - "ENUM" di spec diimplementasikan `VARCHAR` + `CHECK` (lebih mudah dievolusi). Kolom `refresh_tokens`/`otp_attempts` dirancang sendiri (spec hanya menyebut nama tabel) — tinjau di Fase 1.
  - Event antarmodul memakai outbox sendiri per schema, bukan event publication registry Spring Modulith (M5/M7). ArchUnit melarang `ApplicationEventPublisher`/`@EventListener`.
  - Role/schema DB dibuat `DatabaseBootstrap` (admin, advisory lock) **hanya di local/test**. Produksi: role `senyap_<modul>` + schema harus disiapkan infra; app tidak memegang kredensial admin.
  - 7 pool Hikari (default max 5) per instance — tinjau ukuran pool di Fase 5.
  - Profil `api` saat ini cuma men-set `senyap.api.enabled=true` (belum ada controller); worker = relay outbox + job.
  - Peringatan Netty "MacOSDnsServerAddressStreamProvider" di macOS tidak berbahaya.
- Belum diverifikasi: workflow GitHub Actions belum pernah jalan (belum ada remote); `flutter run` di simulator/device (Xcode, Android SDK belum terpasang).
- Langkah berikutnya: minta user review + commit Fase 0, lalu Fase 1 (FS-01: OTP, `phone_hmac`, JWT/JWKS M12, alias, `IdentityApi` batch).

### 2026-11-08 — Dokumen
- Selesai: transkrip Instagram System Design diubah jadi `docs/01–03`. Arah produk diganti ke app anonim dengan DM sebagai inti.
- Keputusan baru (sudah dicatat di `CLAUDE.md`): alias satu per akun, tampil di post/balasan/DM; enkripsi hanya TLS; format waktu relatif.
- Langkah berikutnya: Fase 0 (docker compose, skeleton `api` + `realtime`, migrasi V1, test penjaga anonimitas, skeleton Flutter).
