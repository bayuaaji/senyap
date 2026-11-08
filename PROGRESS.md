# Progress

Diperbarui di akhir setiap sesi. Sesi baru mulai dari sini.

## Status

| Fase | Status | Catatan |
|---|---|---|
| Dokumen (requirements, spec, guide) | ✅ Selesai | 8 Nov 2026 |
| 0 Fondasi | ✅ Selesai | 8 Nov 2026 (belum di-commit) |
| 1 Akun anonim | ⏳ Berikutnya | |
| 2 Bisik / DM | ⬜ Belum | |
| 3 Post, feed, balasan | ⬜ Belum | |
| 4 Keselamatan | ⬜ Belum | Wajib sebelum beta |
| 5 Siap produksi | ⬜ Belum | |

Checklist rinci tiap fase ada di `docs/03-build-guide.md` §4. Centang di sana saat item selesai.

## Fase yang sedang berjalan

**Fase 1 — Akun anonim.** Belum dimulai. Fase 0 selesai; semua file belum di-commit (menunggu persetujuan user).

## Pertanyaan terbuka

- Nomor hotline Indonesia untuk kartu bantuan risiko menyakiti diri (cek sebelum Fase 4).
- Kepatuhan hukum (PSE, UU PDP) perlu dicek dengan konsultan sebelum rilis.
- Penyedia OTP (SMS/WhatsApp) dan API moderasi teks/gambar belum dipilih (dibutuhkan di Fase 1 dan Fase 4).

## Catatan serah terima

Tulis entri baru di paling atas dengan format ini:

```text
### YYYY-MM-DD — Fase N
- Selesai: …
- Sedang dikerjakan / setengah jadi: … (file, apa yang kurang)
- Masalah / keputusan baru: …
- Langkah berikutnya: …
```

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
- Sedang dikerjakan / setengah jadi: tidak ada. Git sudah di-`init` (branch `main`), **belum ada commit**.
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
