# 03 — Guide Pembuatan App

Cara membangun **Senyap** (app anonim, DM sebagai inti) secara bertahap, dari laptop sampai
siap produksi. Spesifikasi lengkapnya ada di `01-system-requirements.md` dan `02-functional-specification.md`.

**Prinsip:**
1. Mulai dari *modular monolith*, kecuali **realtime gateway** yang dari awal sudah jadi proses
   terpisah, karena sifatnya beda (koneksi panjang, stateful, diskalakan berdasarkan jumlah koneksi).
2. **Keselamatan dibangun bersamaan dengan fitur**, bukan setelahnya. Report dan block wajib ada
   sebelum beta pertama. App store juga mewajibkannya untuk app anonim.
3. Anonimitas dijaga oleh **bentuk API**. Ada test otomatis yang gagal jika ada respons berisi user ID.

---

## 1. Arsitektur

### 1.1 Gambaran

```text
                 iOS / Android (Flutter)
                 │ REST (HTTPS)        │ WebSocket (WSS)
                 ▼                     ▼
          ┌────────────┐        ┌───────────────────┐
          │ API (Spring│        │ Realtime gateway   │  N instance, masing-masing
          │ modular    │        │ (Spring WebFlux /  │  memegang ±50k koneksi
          │ monolith)  │        │  Netty)            │
          └─────┬──────┘        └───┬───────────┬────┘
                │                   │           │ presence: user → gateway node
                │  tulis pesan      │           ▼
                │  (seq atomik)     │        Redis ── pub/sub channel per node
                ▼                   │           ▲
          PostgreSQL ◄──────────────┘           │
                │ outbox                        │
                ▼                               │
          Kafka / Redpanda ──► Delivery router ─┘ ──► Push worker ──► FCM / APNs
                │
                ├──► Moderasi worker ──► API moderasi / model ML, hash matching
                ├──► Media worker ──► S3/R2 ──► CDN
                ├──► Counter & hot-score worker ──► Redis ──► Postgres
                └──► Notification worker
```

### 1.2 Alur kirim pesan (jalur terpenting)

1. Client mengirim frame `msg.send` ke gateway lewat WebSocket.
2. Gateway memanggil modul chat (gRPC/HTTP internal, atau langsung jika satu proses di MVP).
3. Modul chat, dalam **satu transaksi**: validasi (status percakapan, block, rate limit) → `UPDATE conversations SET last_seq = last_seq + 1 RETURNING last_seq` → `INSERT messages` → `INSERT outbox(message.created)`.
4. Gateway mengirim `msg.ack {seq}` ke pengirim.
5. Outbox relay mempublikasikan `message.created` ke Kafka (partisi = `conversation_id`).
6. Delivery router membaca event → cek presence penerima di Redis:
   - **Online** → `PUBLISH node:{gateway_id}` → gateway itu mengirim `msg.new` ke socket penerima.
   - **Offline** → push worker mengirim notifikasi via FCM/APNs.
7. Device penerima mengirim `msg.delivered`, lalu `msg.read` saat dibuka → status dikirim balik ke pengirim.

> Kenapa tidak langsung kirim dari gateway pengirim ke gateway penerima? Bisa, dan lebih cepat
> beberapa milidetik. Tapi lewat Kafka, pengiriman tetap terjadi walaupun gateway restart, dan
> push serta moderasi memakai event yang sama. Optimasi jalur langsung bisa ditambahkan nanti.

### 1.3 Apa yang diambil dari transkrip Instagram

| Komponen transkrip | Dipakai di Senyap untuk |
|---|---|
| Image service + blob store + CDN | Gambar latar post dan gambar di DM |
| Distributed queue + worker asinkron | Semua event: pesan, moderasi, counter, notifikasi |
| Like service dengan counter asinkron | Hati dan reply count |
| Distributed cache (Redis) | Feed publik, presence, rate limit, counter |
| API gateway (auth, rate limit) | Nginx/Envoy di depan API dan gateway realtime |
| ~~Follow table + fan-out service~~ | **Tidak dipakai.** Tidak ada follow graph. Feed bersifat pull dari index bersama. |

---

## 2. Tech stack (rekomendasi)

| Lapisan | Pilihan | Alternatif |
|---|---|---|
| API | **Java 21 + Spring Boot 4** + Spring Modulith | Kotlin |
| Realtime gateway | **Spring WebFlux (Netty)** dengan WebSocket mentah dan protokol JSON sendiri (FS-05 §5.5) | Go, Elixir/Phoenix saat koneksi > 1 juta |
| Database | **PostgreSQL 16** (tabel `messages` dipartisi per bulan) | ScyllaDB untuk `messages` saat volume besar |
| Queue | **Redpanda** lokal / Kafka (MSK, Confluent) di produksi | — |
| Cache, presence, pub/sub | **Redis 7** (Cluster di produksi) | Valkey |
| Object storage + CDN | MinIO lokal, **Cloudflare R2 + CDN** atau S3 + CloudFront | — |
| Proses gambar | libvips | — |
| OTP | Penyedia SMS/WhatsApp OTP lokal (pilih yang mendukung deteksi nomor virtual) | Twilio Verify |
| Push | Firebase Cloud Messaging (Android & iOS lewat APNs) | — |
| Moderasi teks | API moderasi (mendukung Bahasa Indonesia) + daftar kata lokal | Model klasifikasi sendiri (v2) |
| Moderasi gambar | API moderasi gambar + hash matching dari program resmi | — |
| Mobile | **Flutter** | React Native |
| Konsol moderator | Web internal (Spring + Thymeleaf/HTMX, atau React) di belakang VPN/SSO | Retool |
| Secret & pepper | KMS (AWS KMS / GCP KMS) | Vault |
| Observability | OpenTelemetry → Grafana stack | Datadog |
| Load test | k6 (punya modul WebSocket) | Gatling |

---

## 3. Struktur repo

```text
messaging app/
├── docs/
├── backend/
│   ├── api/                            Spring Boot modular monolith
│   │   └── src/main/java/app/senyap/
│   │       ├── auth/                   FS-01 (OTP, token, device)
│   │       ├── identity/               alias, encoding ID publik, penjaga anonimitas
│   │       ├── media/                  upload, presign, worker gambar
│   │       ├── posts/                  FS-02, FS-04 (post, balasan, hati)
│   │       ├── feed/                   FS-03 (latest, popular, nearby, category)
│   │       ├── chat/                   FS-05 (conversation, message, seq, inbox)
│   │       ├── notifications/          FS-06 (inbox notifikasi, push)
│   │       ├── safety/                 FS-07, FS-08 (report, block, moderasi, trust)
│   │       ├── admin/                  FS-09 (konsol moderator)
│   │       └── shared/                 TSID, error, cursor, event envelope, outbox
│   ├── realtime/                       Gateway WebSocket (WebFlux)
│   │   └── src/main/java/app/senyap/rt/  session, presence, protokol frame, router
│   └── contracts/                      Skema frame WS + DTO bersama (dipakai api & realtime)
├── mobile/                             Flutter
├── infra/
│   ├── docker-compose.yml              postgres, redis, redpanda, minio (+ bucket media); OTP mock = profil Spring `local`
│   └── k8s/
├── loadtest/                           k6: feed, chat (WS), upload
└── Instagram System Design - Transkrip.docx
```

**Aturan modul:** modul hanya bicara lewat *public API* modulnya atau lewat event. Hanya modul
`identity` yang boleh menerjemahkan `user_id` ↔ `public_id` / alias.

### 3.1 Aturan supaya modul mudah dipecah jadi microservice

Tujuannya: memecah satu modul jadi service sendiri cukup dengan **memindahkan folder, mengganti
satu adapter, dan memindahkan schema-nya**. Tidak perlu mengubah logika bisnis modul lain.

| # | Aturan | Kenapa |
|---|---|---|
| M1 | **Satu modul = satu schema Postgres** (`auth`, `identity`, `posts`, `chat`, …). Tiap modul punya user DB sendiri yang hanya punya akses ke schema-nya. | Saat dipecah, schema dipindah utuh ke database baru. |
| M2 | **Tidak ada foreign key dan JOIN lintas schema.** Referensi ke data modul lain hanya berupa ID biasa (misalnya `posts.author_id` tanpa constraint ke `identity.users`). | FK dan JOIN lintas schema langsung putus begitu databasenya terpisah. |
| M3 | **Pemanggilan sinkron lewat interface** di package root modul, misalnya `IdentityApi.resolveAliases(List<Long>)`. Modul lain hanya bergantung pada interface itu. Tidak ada akses ke class internal, entity, atau repository modul lain. | Saat dipecah, implementasi interface diganti dengan client HTTP/gRPC. Pemanggilnya tidak berubah. |
| M4 | **Interface dirancang seolah-olah sudah lewat jaringan**: batch (`List` masuk, `Map` keluar), DTO yang immutable dan bisa diserialisasi, tanpa entity JPA, tanpa lazy loading, tanpa transaksi yang melintasi modul. | Kalau tidak, interface yang "murah" di dalam satu proses akan jadi N+1 call jaringan setelah dipecah. |
| M5 | **Komunikasi asinkron lewat Kafka sejak hari pertama**, bukan event in-memory Spring. Tiap modul punya topik sendiri (`chat.events`, `posts.events`, …) dan consumer group sendiri. | Event sudah lewat broker, jadi memecah modul tidak mengubah alur event sama sekali. |
| M6 | **Satu transaksi hanya menyentuh satu schema.** Konsistensi antar modul memakai outbox + event (eventual), bukan transaksi bersama. | Transaksi terdistribusi tidak tersedia setelah dipecah. |
| M7 | **Outbox per modul** (`chat.outbox`, `posts.outbox`, …), dengan relay yang berjalan per modul. | Outbox ikut pindah bersama schema-nya. |
| M8 | **Data milik modul lain yang sering dibaca disalin lewat event** (read model lokal). Contoh: `posts` menyimpan salinan alias penulis dari event `identity.alias_changed`, kalau memanggil `IdentityApi` per request terlalu mahal. | Mengurangi ketergantungan sinkron, sehingga satu service mati tidak menjatuhkan yang lain. |
| M9 | **Konfigurasi per modul** dengan prefix sendiri (`senyap.chat.*`, `senyap.posts.*`), termasuk koneksi DB dan topik. | Saat dipecah, konfigurasinya tinggal dipindah. |
| M10 | **Batas modul diuji otomatis di CI**: `ApplicationModules.verify()` (Spring Modulith) + test ArchUnit untuk M2–M4. Build gagal kalau ada pelanggaran. | Aturan yang tidak diuji pasti dilanggar diam-diam. |
| M11 | **Kontrak API dan event disimpan di `backend/contracts/`** (OpenAPI untuk REST, JSON Schema untuk event dan frame WebSocket), dan diversi. | Service yang terpisah butuh kontrak yang jelas dan kompatibel ke belakang. |
| M12 | **Auth tidak bergantung pada session in-memory.** JWT diverifikasi dengan public key (JWKS), jadi service mana pun bisa memverifikasinya tanpa memanggil modul `auth`. | Service hasil pecahan bisa langsung memverifikasi token sendiri. |

**Langkah memecah satu modul** (contoh: `chat`):
1. Buat project baru dari folder `backend/api/.../chat` + `shared/`.
2. Pindahkan schema `chat` ke database sendiri (dump/restore, atau replikasi logis tanpa downtime).
3. Di monolith, ganti implementasi `ChatApi` dengan client HTTP/gRPC yang memanggil service baru.
4. Pindahkan consumer group `chat` ke service baru. Topik Kafka tidak berubah.
5. Arahkan route `/v1/conversations/**` di gateway ke service baru.
6. Hapus folder `chat` dari monolith.

---

## 4. Fase pembangunan

Perkiraan untuk 1–2 engineer. Urutannya sengaja menaruh **DM lebih awal**, karena DM adalah inti
produk dan bagian paling sulit secara teknis.

### Fase 0 — Fondasi (± 1 minggu)

- [x] `infra/docker-compose.yml`: Postgres, Redis, Redpanda, MinIO, dan mock OTP (kode selalu `000000` di lokal).
- [x] Skeleton `api` (Spring Modulith) dan `realtime` (WebFlux), health check.
- [x] `shared/`: TSID, format error, cursor, event envelope, **outbox + relay**.
- [x] Flyway **per modul** (`db/migration/{modul}/V1__init.sql`, satu schema + satu user DB per modul): semua tabel di §2 functional spec, termasuk partisi `messages` dan tabel `outbox` per schema.
- [x] Test batas modul: `ApplicationModules.verify()` + ArchUnit untuk aturan M2–M4 (§3.1).
- [x] **Test penjaga anonimitas**: interceptor di test integrasi yang memindai semua respons JSON dan gagal jika menemukan field `user_id`, `author_id`, `sender_id`, atau nilai yang cocok dengan user ID mana pun.
- [x] CI: build, unit test, integration test (Testcontainers).
- [x] Skeleton Flutter: tab Feed, Tulis, Pesan, Notifikasi, Pengaturan.

**Selesai jika:** `docker compose up` + kedua service jalan, migrasi teraplikasi, test penjaga lulus.

### Fase 1 — Akun anonim (± 1 minggu) · FS-01

- [x] OTP: request, verify, rate limit per nomor dan per IP.
- [x] `phone_hmac` dengan pepper dari env/KMS. Nomor asli tidak pernah ditulis ke DB atau log.
- [x] JWT + refresh token terikat device. Registrasi push token.
- [x] Modul `identity`: `public_id`, generator alias (`{hewan} {warna} #tag`, unik global), suggest → simpan, batas ganti 7 hari, `alias_history`.
- [x] Hapus akun: tandai `DELETED`, lalu job purge (post, balasan, pesan milik user di-anonimkan menjadi "[dihapus]"). *Fase 1: purge memutus nomor HP + event `user.purged`; consumer anonimisasi konten dibuat bersama modul posts/chat (Fase 2/3).*
- [x] Flutter: layar pembuka anonimitas, konfirmasi usia, OTP, layar buat profil (alias + "Acak lagi"), pengaturan profil (ganti alias), izin. *Layar izin baru penjelasan; permintaan izin OS menyusul bersama push (Fase 2) dan feed Sekitar (Fase 3).*

**Selesai jika:** register → login di device lain → refresh → hapus akun, semuanya lulus test integrasi.

### Fase 2 — Bisik / DM (± 3 minggu) · FS-05 ★

Kerjakan dulu dengan percakapan yang dibuat lewat seed data, karena post baru ada di Fase 3.

- [x] Request → accept / decline / expire. Kuota harian. Cek block.
- [x] Kirim pesan dengan `seq` atomik + `client_msg_id` idempotent + outbox.
- [x] Gateway: autentikasi WebSocket, heartbeat, presence (hash `presence:{public_id}` field `{node}|{session}`, TTL 60 dtk; multi-device).
- [x] Delivery router: konsumsi `message.created` → pub/sub ke node penerima, atau ke push.
- [x] Status `delivered` / `read`, indikator mengetik (hanya di memori, tidak lewat Kafka).
- [x] REST: inbox, requests, riwayat (`before_seq`), gap fill (`after_seq`), fallback kirim.
- [x] Tarik pesan, keluar, block.
- [x] Gambar di DM (presigned upload + blur sampai diketuk). Moderasi gambar menyusul Fase 4.
- [x] Push worker (FCM) dengan collapse per percakapan. Pengirim masih mock (`LoggingPushSender`); FCM nyata + registrasi token di app menyusul Fase 5.
- [x] Flutter: inbox, tab permintaan, layar chat dengan antrean kirim offline (simpan lokal di SQLite/Drift, kirim ulang dengan `client_msg_id` yang sama), reconnect dengan backoff, sinkron `last_seq`.

**Selesai jika:**
- Dua device chat realtime dengan latensi p95 < 500 ms.
- Matikan jaringan pengirim, kirim 5 pesan, nyalakan lagi → kelima pesan terkirim sekali, berurutan, tidak ganda.
- Restart gateway saat chat berlangsung → client reconnect otomatis, tidak ada pesan hilang.
- Penerima offline → menerima push → buka app → pesan lengkap.

### Fase 3 — Post, feed, balasan (± 2 minggu) · FS-02, FS-03, FS-04

- [x] Galeri latar bawaan (seed ±300 gambar per kategori). Sementara placeholder generatif buatan sendiri; aset final berlisensi belum ada.
- [x] Upload latar sendiri: pipeline gambar dari transkrip + blur + overlay.
- [x] Post: buat, lihat, hapus. Lokasi kasar (geohash 5 + kota).
- [x] Feed: latest, popular (`hot_score` worker tiap 1 menit), nearby (sel + 8 tetangga), kategori.
- [x] Balasan dengan alias akun (pembuat post diberi lencana Penulis). Hati dengan counter terbatch.
- [x] Tombol **Bisik** di post dan balasan → menyambung ke Fase 2.
- [x] Flutter: kartu post (teks di atas gambar, blurhash saat memuat), editor post, layar balasan.

**Selesai jika:** user A membuat post, user B melihatnya di Terbaru dan Sekitar, membalas, lalu
membuka Bisik ke A dari balasan itu, dan A menerimanya.

### Fase 4 — Keselamatan (± 2 minggu) · FS-07, FS-08, FS-09 — **wajib sebelum beta**

- [x] Report dengan snapshot. Block dari konten. Filter block dua arah di feed dan chat.
- [x] Filter kata + pola doxxing (nomor HP, NIK, alamat) di konten publik.
- [x] Integrasi API moderasi teks dan gambar. Antrean berprioritas P0/P1/P2. (Port `ModerationClassifier` + `HeuristicClassifier` mock; penyedia nyata belum dipilih.)
- [x] Hash matching gambar + prosedur eskalasi P0 (tertulis, dengan penanggung jawab). (`docs/05-prosedur-eskalasi-p0.md`; nama penanggung jawab dan daftar hash resmi diisi user.)
- [x] Kartu bantuan untuk risiko menyakiti diri (hotline Indonesia, cek ulang nomornya sebelum rilis). (Nomor masih `BELUM-DIVERIFIKASI`; app gagal start di produksi sampai diverifikasi.)
- [x] Trust score + kuota dinamis + shadow restriction.
- [x] Konsol moderator: antrean, detail kasus, aksi, audit log. Akses lewat SSO dan peran.
- [x] Halaman Syarat, Kebijakan Privasi (jelaskan A6 dan pilihan enkripsi di §7 requirements), dan Pedoman Komunitas. (Draf; perlu konsultan hukum.)

**Selesai jika:** semua skenario uji di §9 system requirements no. 5 lulus, dan moderator bisa
memproses laporan P0 end-to-end di staging.

### Fase 5 — Siap produksi (± 2 minggu)

- [x] Infra produksi: Postgres managed (dengan PITR), Redis Cluster, Kafka managed, R2/S3 + CDN. (Sebagai kode: `infra/k8s` Kustomize + `bootstrap.sql`; akun cloud belum ada.)
- [x] Gateway realtime di belakang load balancer yang mendukung WebSocket (idle timeout ≥ 120 detik). Drain koneksi dengan anggun saat deploy. (Tutup 1012 disebar + preStop; `GatewayDrainTest`.)
- [x] OpenTelemetry: trace dari `msg.send` sampai `msg.new`. Dashboard: koneksi aktif, latensi pesan, lag consumer, antrean moderasi. (`infra/observability`.)
- [x] Log tanpa isi pesan dan tanpa nomor HP (uji dengan test).
- [ ] Load test k6: 50.000 koneksi WS + 3.000 pesan/s + feed 2.000 rps. (Skrip + panduan siap, versi kecil lulus di laptop; target penuh menunggu staging.)
- [x] Pen-test ringan: IDOR di semua endpoint `conversations/*`, enumerasi alias, dan timing attack pada block. (`ProductionReadinessIT`.)
- [ ] Langkah kepatuhan di §8 requirements dicek bersama konsultan hukum. (Checklist: `docs/06-checklist-kepatuhan-dan-beta.md`.)
- [ ] Beta tertutup lewat TestFlight dan Play Console internal testing. (Checklist: `docs/06-checklist-kepatuhan-dan-beta.md`.)

### Fase 6 — v2 & jalur skala

| Pemicu | Langkah |
|---|---|
| Fitur | Pesan suara, foto sekali-lihat, pesan hilang otomatis, ruang obrolan, search. |
| Privasi | Evaluasi E2EE (Signal Protocol / MLS) dengan *report-by-forwarding*. |
| Koneksi > ~500k | Tambah node gateway (horizontal). Kalau biaya memori per koneksi jadi masalah, tulis ulang gateway saja di Go/Elixir. Protokol frame tetap sama. |
| `messages` > beberapa TB atau tulis > ~10k/s | Pindahkan `messages` ke ScyllaDB/Cassandra (partition key `conversation_id`, clustering `seq`). `seq` dialokasikan oleh layanan khusus atau Redis `INCR` per percakapan. |
| Feed lambat | Precompute halaman feed per kategori/sel ke Redis. Read replica Postgres. |

---

## 5. Keputusan desain penting

1. **`seq` per percakapan, bukan timestamp.** Jam device dan server tidak bisa dipercaya untuk urutan. Nomor urut atomik di server memberi urutan yang sama di kedua sisi dan memudahkan deteksi celah.
2. **`client_msg_id` untuk idempotensi.** Jaringan HP sering putus di tengah jalan. Client boleh mengirim ulang berkali-kali tanpa membuat pesan ganda.
3. **Tulis ke DB sebelum ack.** Pesan yang sudah dicentang satu tidak boleh hilang (NFR-15).
4. **Outbox pattern.** Simpan pesan dan publikasi event terjadi atomik, jadi tidak ada pesan yang tersimpan tapi tidak pernah terkirim.
5. **Gateway terpisah sejak awal.** Deploy API (sering) tidak memutus koneksi chat semua user.
6. **Request sebelum chat.** Pola request → accept memotong sebagian besar spam dan pelecehan di app anonim.
7. **Block dan report merujuk konten, bukan user.** Client tidak pernah butuh user ID, jadi tidak ada yang bisa bocor.
8. **Anonimitas diuji, bukan dipercaya.** Test penjaga respons dan test log dijalankan di CI.
9. **Tanpa fan-out.** Tanpa follow graph, feed adalah query bersama plus cache pendek. Jauh lebih sederhana daripada desain Instagram di transkrip.

---

## 6. Menjalankan secara lokal

```bash
cd infra && docker compose up -d
```

```bash
cd backend && ./mvnw -pl api -am spring-boot:run        # :8180, profil local,api,worker
```

```bash
cd backend && ./mvnw -pl realtime -am spring-boot:run   # :8190, ws://localhost:8190/v1/ws
```

```bash
cd mobile && flutter run
```

Detail perintah lain (test tunggal, api/worker terpisah) ada di `CLAUDE.md` bagian Perintah.

---

## 7. Definition of Done per fitur

- Endpoint dan frame WebSocket sesuai `02-functional-specification.md`, termasuk kode error.
- Test penjaga anonimitas lulus untuk endpoint baru.
- Unit test aturan bisnis + integration test (Testcontainers).
- Consumer event idempotent dan sudah diuji dengan pengiriman ganda.
- Konten baru (jenis teks atau gambar apa pun) sudah melewati moderasi dan bisa di-report.
- Metrik & trace muncul di dashboard, log bebas data sensitif.
- Layar Flutter menangani loading, kosong, error, offline, dan reconnect.
