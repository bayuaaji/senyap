# Senyap — app anonim dengan DM sebagai inti

Nama kerja: **Senyap**. App mobile (iOS + Android) untuk post anonim (teks di atas gambar latar)
dan **Bisik**, DM anonim 1-on-1 yang selalu berawal dari sebuah post atau balasan.

## Mulai sesi baru di sini

1. Baca `PROGRESS.md`: fase yang sedang berjalan, apa yang sudah selesai, dan catatan sesi terakhir.
2. Baca hanya bagian dokumen yang relevan dengan fase itu (lihat tabel di bawah). Jangan baca semuanya.
3. Kerjakan checklist fase di `docs/03-build-guide.md` §4.
4. Sebelum sesi berakhir atau konteks mulai penuh: perbarui `PROGRESS.md` (centang item, tulis catatan serah terima).

| Fase | Baca |
|---|---|
| 0 Fondasi | build guide §3, §4 Fase 0, §5 · functional spec §2 (model data) |
| 1 Akun anonim | functional spec §1.1, FS-01 · requirements §2, §7 |
| 2 Bisik / DM | functional spec FS-05, FS-06, §5 · build guide §1.2 |
| 3 Post, feed | functional spec FS-02, FS-03, FS-04, §4 |
| 4 Keselamatan | functional spec FS-07, FS-08, FS-09, §6 · requirements §8 |
| 5 Produksi | build guide Fase 5 · requirements §6, §9 |

Dokumen:
- `docs/01-system-requirements.md`: tujuan, prinsip anonimitas A1–A6, NFR, keputusan enkripsi, kepatuhan
- `docs/02-functional-specification.md`: model data, fitur FS-01–FS-10, API, protokol WebSocket, event, rate limit
- `docs/03-build-guide.md`: arsitektur, stack, struktur repo, fase, keputusan desain
- `docs/04-tech-decisions.md`: alasan tiap pilihan teknologi, alternatif, trade-off, kapan ditinjau ulang. Baca sebelum mengusulkan ganti teknologi.

## Keputusan yang sudah final (jangan diubah tanpa persetujuan user)

- **Alias**: satu per akun, diacak saat profil dibuat, melekat sampai diubah di pengaturan (maks 1× per 7 hari). Format `{hewan} {warna} #tag4digit`, unik global, dipilih dari daftar (bukan teks bebas). Ditampilkan di post, balasan, dan DM. Pembuat post di balasannya diberi lencana Penulis.
- **Enkripsi**: hanya di jalur jaringan (TLS). Isi pesan disimpan **plaintext**. Tidak ada enkripsi di level aplikasi. E2EE baru dievaluasi di v2.
- **Waktu**: server mengirim `created_at` dibulatkan ke menit. Client yang memformat "x menit/jam/hari/bulan/tahun yang lalu" (functional spec §4.1).
- **Tanpa follow graph dan fan-out.** Feed bersifat pull dari index bersama + cache pendek.
- **DM**: request → accept sebelum chat terbuka. `seq` per percakapan dialokasikan atomik di DB. `client_msg_id` untuk idempotensi. Pesan ditulis ke DB sebelum ack.
- **Identitas**: client tidak pernah menerima `user_id` atau nomor HP. Yang dikirim hanya `public_id` + alias. Nomor HP disimpan sebagai HMAC.

## Stack

- Backend: Java 21, Spring Boot 4 (4.1.x), Spring Modulith 2.1, Flyway, Maven (multi-module, `./mvnw` wrapper). Boot 4 memakai Jackson 3 (`tools.jackson.*`) dan starter modular (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, …).
- Realtime gateway: Spring WebFlux (Netty), WebSocket mentah dengan protokol JSON (functional spec FS-05 §5.5)
- Data: PostgreSQL 16, Redis 7, Redpanda (Kafka API), MinIO (S3 API; image `cgr.dev/chainguard/minio` karena `minio/minio` tidak lagi publik)
- Mobile: Flutter
- Test: JUnit 5, Testcontainers, k6

## Struktur

```text
backend/api/        modular monolith (auth, identity, media, posts, feed, chat, notifications, safety, admin, shared)
backend/realtime/   gateway WebSocket
backend/contracts/  skema frame WS + DTO bersama
mobile/             Flutter
infra/              docker-compose.yml, k8s/
loadtest/           skrip k6
docs/               spesifikasi
```

## Perintah

Semua sudah dicoba jalan di akhir Fase 0 (8 Nov 2026). Port: api 8180, worker 8182, realtime 8190
(8080 sengaja dihindari karena sering dipakai app lain).

```bash
cd infra && docker compose up -d          # postgres, redis, redpanda, minio (+ bucket media)
cd backend && ./mvnw verify               # unit + Modulith/ArchUnit + IT Testcontainers + penjaga anonimitas
cd backend && ./mvnw -pl api -am spring-boot:run        # profil default local,api,worker → :8180
cd backend && ./mvnw -pl realtime -am spring-boot:run   # WebSocket ws://localhost:8190/v1/ws
cd mobile && flutter analyze && flutter test
```

- `-am` wajib (supaya modul `contracts` ikut di-build); tanpa itu Maven tidak menemukan `contracts`.
- API dan worker terpisah: `./mvnw -pl api -am spring-boot:run -Dspring-boot.run.profiles=local,api` dan
  `SENYAP_API_PORT=8182 ./mvnw -pl api -am spring-boot:run -Dspring-boot.run.profiles=local,worker`.
- Health: `curl localhost:8180/actuator/health` (detail per DataSource modul, Redis, MinIO), `curl localhost:8190/actuator/health`.
- Satu test: `./mvnw -pl api -am test -Dtest='CursorTest' -Dsurefire.failIfNoSpecifiedTests=false`.
  Integration test (`*IT`) jalan di fase `verify` lewat failsafe: `./mvnw -pl api -am verify -Dit.test='OutboxRelayIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.
- `flutter run` belum bisa: Xcode/Android SDK belum terpasang.

## Aturan kode

- **Modular monolith harus siap dipecah jadi microservice.** Ikuti aturan M1–M12 di `docs/03-build-guide.md` §3.1. Intinya:
  - satu modul = satu schema Postgres;
  - tidak ada FK atau JOIN lintas schema;
  - pemanggilan sinkron hanya lewat interface batch dengan DTO;
  - event lewat Kafka (bukan event in-memory);
  - satu transaksi = satu schema;
  - outbox per modul;
  - batas modul diuji di CI (Spring Modulith + ArchUnit).
- Hanya modul `identity` yang menerjemahkan `user_id` ↔ `public_id` / alias.
- Semua tulis + publish event lewat **outbox** dalam satu transaksi: `<modul>Outbox.append(...)` di dalam
  `@Transactional("<modul>Tx")` (atau `<modul>Database.tx()`). `@Transactional` tanpa nama TM ditolak ArchUnit (M6).
- Tiap modul ber-schema punya `internal/<Modul>PersistenceConfig` (DataSource, Flyway `db/migration/<modul>`,
  `JdbcClient`, TM, outbox, topik `<modul>.events`). Tambah tabel = tambah `V{n}__*.sql` di folder modulnya,
  tanpa menyebut schema lain (diuji `MigrationRulesTest`).
- Consumer event wajib idempotent berdasarkan `event_id`.
- ID: TSID, dikirim ke client sebagai string. Paginasi selalu cursor, tidak pernah offset.
- Format error: `{ "error": { "code", "message", "request_id" } }`. Kode error mengikuti functional spec §4.
- Jangan pernah menulis isi pesan, nomor HP, atau token ke log.
- Teks yang tampil ke user memakai Bahasa Indonesia.
- Setiap endpoint baru wajib lolos test penjaga anonimitas (tidak ada `user_id`/`author_id`/`sender_id` di respons): tulis integration test yang `extends IntegrationTest`, penjaganya otomatis memindai semua respons JSON.

## Hemat konteks

- Satu sesi per fase (atau per bagian besar fase). Jangan lanjut ke fase berikutnya di sesi yang sama.
- Jalankan test yang relevan saja (`./mvnw -pl api test -Dtest='ChatServiceTest'`), bukan seluruh suite, kecuali di akhir fase.
- Output build/test yang panjang: ambil bagian error-nya saja (`| tail -50` atau grep).
- Hindari screenshot simulator kecuali benar-benar perlu memeriksa tampilan.
