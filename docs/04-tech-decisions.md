# 04 — Keputusan Teknologi (dan alasannya)

Setiap pilihan di bawah ditulis dengan format: **kenapa dipilih**, **alternatif yang
dipertimbangkan**, **harga yang dibayar** (trade-off), dan **kapan ditinjau ulang**.

Konteks yang mendasari semua keputusan:
- Tim kecil (1–2 engineer) dengan latar belakang **backend Java**.
- Beban utama: **banyak koneksi chat yang lama terbuka tapi jarang mengirim data**, ditambah REST CRUD biasa.
- Target 12–18 bulan: 1 juta DAU, 200 ribu koneksi WebSocket bersamaan, ~600 pesan/s rata-rata, ~6.000/s puncak.
- Harus bisa dipecah jadi microservice nanti (build guide §3.1).

---

## Backend

### Java 21 + Spring Boot 4 (untuk `api`)

> Fase 0 (8 Nov 2026): versi stabil terbaru saat itu Spring Boot **4.1.2** + Spring Modulith **2.1.1**, jadi dipakai Boot 4, bukan 3. Konsekuensi: Jackson 3 (`tools.jackson.*`), starter modular, Testcontainers 2.

**Kenapa**
- **Keahlian tim.** Bahasa dan framework yang sudah dikuasai adalah faktor kecepatan terbesar untuk tim kecil. Belajar stack baru sambil membangun produk baru menggandakan risiko.
- **Virtual threads (Java 21).** Kode bisa tetap ditulis gaya blocking yang sederhana (`jdbcTemplate.query(...)`), tapi satu instance bisa melayani ribuan request yang sedang menunggu DB atau Redis, tanpa thread pool besar. Ini menghilangkan alasan utama memakai reactive untuk REST.
- **Ekosistem lengkap dan matang**: Spring Security (JWT), Spring Kafka, Spring Data Redis, Flyway, Testcontainers, OpenTelemetry. Hampir tidak ada yang perlu ditulis dari nol.
- **Rekrutmen.** Engineer Java/Spring paling mudah dicari di Indonesia (banyak dari perbankan dan fintech).

**Alternatif**
- *Go*: lebih hemat memori, startup cepat. Tapi ekosistem untuk aturan modul, outbox, dan migrasi lebih tipis, dan tim harus belajar bahasa baru.
- *Node.js (NestJS)*: cepat dibuat, tapi tipe datanya lebih longgar untuk domain yang punya banyak aturan, dan pekerjaan CPU (moderasi, gambar) memblokir event loop.
- *Kotlin + Spring*: valid, lebih ringkas. Bisa diadopsi bertahap kapan saja karena bisa bercampur dengan Java.

**Trade-off:** pemakaian memori lebih besar (±300–500 MB per instance) dan startup lebih lambat dibanding Go. Untuk skala ini, selisih biayanya kecil dibanding kecepatan pengembangan.

**Tinjau ulang** jika biaya infrastruktur API menjadi masalah besar. Opsi pertama: GraalVM native image, bukan ganti bahasa.

### Spring Modulith

**Kenapa**
- **Batas modul diuji, bukan cuma disepakati.** `ApplicationModules.verify()` membuat build gagal kalau modul A mengakses class internal modul B. Ini fondasi agar monolith bisa dipecah nanti.
- **Event publication registry** = outbox bawaan. Event yang gagal dikirim dicatat dan diulang.
- Bisa menghasilkan diagram modul otomatis dari kode, jadi dokumentasi arsitektur selalu sesuai kenyataan.

**Alternatif:** package biasa + ArchUnit saja. Bisa, tapi semua harus ditulis sendiri. ArchUnit tetap dipakai sebagai pelengkap untuk aturan yang tidak dicakup Modulith (misalnya larangan JOIN lintas schema di query).

**Trade-off:** satu lapisan konsep tambahan untuk dipelajari, tapi kecil.

### Spring WebFlux (Netty) untuk gateway `realtime`

Ini keputusan yang paling sering dipertanyakan, jadi alasannya dirinci.

**Sifat beban gateway berbeda dengan API:**

| | API (REST) | Gateway (WebSocket) |
|---|---|---|
| Umur koneksi | Milidetik | Menit sampai jam |
| Jumlah bersamaan | Ratusan–ribuan | **Puluhan ribu per instance** |
| Aktivitas per koneksi | Satu request, satu respons | Sebagian besar diam, sesekali ada frame |
| Pekerjaan per pesan | Query DB, logika bisnis | Teruskan frame, cek presence, kirim ack |

**Kenapa WebFlux/Netty:**
- **Model event loop.** Beberapa thread (≈ jumlah core) melayani semua koneksi. Satu koneksi yang diam hanya memakan beberapa KB buffer, bukan satu thread. 50.000 koneksi per instance jadi realistis.
- **Kontrol penuh atas WebSocket**: frame, ping/pong, ukuran buffer, timeout idle, dan *backpressure*. Kalau HP penerima lambat membaca, Netty bisa menahan pengiriman dan tidak menumpuk pesan di memori server sampai habis.
- **Cocok dengan Redis reaktif (Lettuce).** Gateway berlangganan channel pub/sub `node:{id}` dan meneruskan pesan ke socket yang tepat dalam satu alur non-blocking, tanpa thread perantara.
- **Tetap di ekosistem Spring/Java.** Konfigurasi, security (verifikasi JWT), metrik, tracing, dan library kontrak (`backend/contracts`) dipakai bersama dengan `api`.

**Kenapa bukan Spring MVC + virtual threads untuk gateway juga?**
Virtual threads membuat *blocking I/O* murah, dan itu sangat cocok untuk REST. Tapi dukungan WebSocket di stack servlet (Tomcat/Jetty) tetap lebih sedikit memberi kontrol atas backpressure dan siklus hidup frame. Gateway hanya meneruskan data tanpa query DB yang rumit, sehingga kode reaktifnya tetap kecil dan sederhana. Kerumitan reactive cuma dibayar di satu tempat yang memang butuh.

**Kenapa bukan STOMP (Spring WebSocket messaging)?**
STOMP membawa semantik broker (subscribe ke destination) yang tidak kita butuhkan, dan tidak punya konsep `seq`, `ack` per pesan, atau gap fill. Protokol JSON sendiri (functional spec FS-05 §5.5) lebih kecil dan persis sesuai kebutuhan.

**Kenapa bukan Go atau Elixir/Phoenix?**
Keduanya unggul untuk *jutaan* koneksi per node (Elixir: proses ringan BEAM; Go: goroutine). Tapi untuk target 200 ribu koneksi (± 4–6 instance gateway), Netty sudah cukup, dan tim tidak perlu memelihara bahasa kedua.

**Kenapa API tidak sekalian pakai WebFlux?**
Kode reaktif (Mono/Flux) lebih sulit ditulis, di-debug, dan dibaca stack trace-nya, dan JDBC/JPA tidak reaktif. Dengan virtual threads, REST mendapat skalabilitas yang hampir sama dengan kode yang jauh lebih sederhana.

**Trade-off:** dua gaya pemrograman dalam satu repo (blocking di `api`, reaktif di `realtime`). Diatasi dengan menjaga gateway tetap tipis: tidak ada logika bisnis di sana.

**Tinjau ulang** jika koneksi bersamaan mendekati ±1 juta atau memori per koneksi jadi biaya utama. Protokol frame sengaja dibuat independen bahasa, jadi gateway bisa ditulis ulang di Go/Elixir tanpa mengubah app maupun `api`.

---

## Data

### PostgreSQL 16

**Kenapa**
- **Transaksi ACID dibutuhkan di jalur paling penting.** Alokasi `seq` pesan (`UPDATE … last_seq + 1 RETURNING`) harus atomik bersama `INSERT` pesan dan `INSERT` outbox. Postgres melakukannya dalam satu transaksi sederhana.
- **Datanya relasional**: percakapan ↔ anggota ↔ pesan, block, report. Constraint unik (satu Bisik per orang per post, alias unik) ditegakkan oleh database, bukan kode.
- **Volume masih jauh dalam kemampuannya.** 600 insert/s rata-rata dan 6.000/s puncak bisa ditangani satu primary Postgres yang dikonfigurasi baik.
- **Partisi per bulan** untuk `messages` membuat retensi murah: menghapus data lama = `DROP PARTITION`, bukan `DELETE` jutaan baris.
- **Schema per modul** (aturan M1) didukung secara native.
- Tersedia managed di semua cloud (RDS, Cloud SQL, Neon, Supabase), lengkap dengan PITR.

**Alternatif**
- *Cassandra/ScyllaDB* (disebut di transkrip): unggul untuk tulis sangat besar, tapi tidak punya transaksi multi-tabel. Alokasi `seq` jadi masalah terpisah. Operasionalnya juga jauh lebih berat untuk tim kecil.
- *MongoDB*: fleksibel, tapi constraint unik lintas dokumen dan transaksi lebih terbatas, dan datanya memang relasional.

**Trade-off:** skala horizontal tulis tidak semudah Cassandra.

**Tinjau ulang** saat `messages` > beberapa TB atau tulis bertahan > ±10.000/s. Jalurnya: pindahkan **hanya** tabel `messages` ke ScyllaDB (partition key `conversation_id`, clustering `seq`). Karena schema `chat` sudah terisolasi, perpindahan ini tidak menyentuh modul lain.

### Redis 7

**Kenapa:** satu alat untuk empat kebutuhan yang semuanya butuh latensi < 1 ms:

| Kebutuhan | Fitur Redis |
|---|---|
| Presence (user online di gateway mana) | `SET key value EX 60`. Kedaluwarsa sendiri kalau gateway mati. |
| Routing pesan ke gateway | Pub/sub per node |
| Feed Populer, counter hati | Sorted set, `HINCRBY` |
| Rate limit & kuota | `INCR` + TTL, atomik |

**Alternatif:** memcached (tidak punya sorted set / pub/sub), Hazelcast (lebih berat, cluster in-process).

**Trade-off:** pub/sub Redis bersifat *fire-and-forget*: kalau gateway sedang restart, pesan pub/sub hilang. Ini aman karena pesannya sudah tersimpan di Postgres, dan client melakukan sinkron `last_seq` saat reconnect. Redis bukan sumber kebenaran, hanya jalur cepat.

### Kafka (Redpanda di lokal)

**Kenapa**
- **Satu event dibaca banyak consumer secara independen.** `message.created` dipakai delivery router, push worker, dan moderasi, masing-masing dengan consumer group dan kecepatannya sendiri. Di Kafka ini bawaan.
- **Urutan per kunci.** Partisi berdasarkan `conversation_id` menjamin event satu percakapan diproses berurutan.
- **Replay.** Log disimpan berhari-hari. Kalau ada bug di consumer, offset bisa diulang untuk memproses ulang. Consumer baru (misalnya search di v2) bisa membaca dari awal.
- **Batas yang sama dengan microservice nanti** (aturan M5): topik per modul sudah jadi kontrak antar service.

**Kenapa Redpanda untuk lokal dan dev:** API-nya kompatibel dengan Kafka (kode tidak berubah), berupa satu binary tanpa ZooKeeper, dan memakai RAM jauh lebih kecil, sehingga laptop tidak berat.

**Alternatif**
- *RabbitMQ* (disebut di transkrip): bagus untuk antrean tugas, tapi pesan hilang setelah di-ack (tidak ada replay), dan menyebar satu pesan ke banyak consumer butuh exchange dan antrean terpisah per consumer.
- *Redis Streams*: ringan, tapi durabilitas dan operasional retensi besar lebih lemah. Menyatukan cache dan event di satu sistem juga membuat satu kegagalan menjatuhkan keduanya.

**Trade-off:** Kafka adalah komponen yang paling berat dioperasikan. Di produksi pakai yang managed (MSK, Confluent, Redpanda Cloud).

**Tinjau ulang:** tidak perlu, Kafka cukup sampai skala sangat besar.

### Object storage S3-compatible (MinIO lokal, Cloudflare R2 / S3 produksi) + CDN

**Kenapa**
- **Presigned upload**: HP mengupload langsung ke storage. Server aplikasi tidak memegang byte gambar sama sekali (pelajaran dari transkrip).
- **R2 tidak mengenakan biaya egress.** App berbasis gambar didominasi biaya bandwidth keluar, dan di S3 + CloudFront ini jadi pos biaya terbesar.
- Cloudflare punya PoP di Indonesia, jadi gambar dekat dengan user.
- Semua memakai S3 API, sehingga MinIO di lokal, R2, dan S3 bisa ditukar hanya lewat konfigurasi.

**Trade-off:** fitur R2 lebih sedikit daripada S3 (misalnya event notification yang lebih terbatas). Karena itu pipeline dipicu oleh `POST /v1/posts` dari app, bukan oleh event dari storage.

### libvips (pemrosesan gambar)

**Kenapa:** 4–8× lebih cepat dan memakai memori jauh lebih kecil daripada ImageMagick, karena memproses gambar secara *streaming* (per potongan, bukan seluruh gambar di memori). Mendukung HEIC (format default iPhone) dan WebP.

---

## Keamanan & identitas

### JWT (access token pendek) + refresh token di DB

**Kenapa**
- **Verifikasi tanpa memanggil siapa pun.** Gateway realtime dan service hasil pecahan nanti cukup memverifikasi tanda tangan dengan public key (JWKS). Tidak ada session store terpusat yang jadi titik macet (aturan M12).
- **Umur 15 menit** membatasi kerugian kalau token bocor. Refresh token disimpan di DB sehingga **bisa dicabut** (logout, ban, hapus akun) dan dirotasi setiap dipakai untuk mendeteksi pencurian.

**Alternatif:** session di Redis (mudah dicabut, tapi setiap request butuh lookup ke Redis), Keycloak (terlalu berat untuk login OTP sederhana).

### TSID untuk ID

**Kenapa**
- **Berurut waktu**: insert ke B-tree index selalu di ujung kanan, tidak acak. Ini berbeda dengan UUIDv4 yang membuat index terfragmentasi dan memperlambat tulis di tabel besar.
- **64-bit**: setengah ukuran UUID, sehingga index lebih kecil.
- **Dibuat di aplikasi tanpa koordinasi**: tidak bergantung pada sequence DB, jadi tetap aman setelah sharding atau pemecahan service.
- Bisa langsung dipakai sebagai cursor paginasi.

**Alternatif:** Snowflake (butuh pengelolaan worker ID), UUIDv7 (juga berurut waktu, tapi 128-bit).

`public_id` sengaja memakai **UUIDv4 acak**, justru karena tidak boleh berurutan atau bisa ditebak.

### HMAC untuk nomor HP

**Kenapa HMAC, bukan hash biasa:** nomor HP Indonesia hanya ada ±ratusan juta kemungkinan. Hash biasa (SHA-256) bisa dibalik dengan mencoba semua nomor dalam hitungan jam. HMAC dengan *pepper* yang disimpan di KMS (bukan di DB) membuat dump database saja tidak cukup untuk membalikkan hash.

---

## Mobile

### Flutter

**Kenapa**
- **Satu codebase untuk iOS dan Android**, krusial untuk tim kecil.
- **Tampilan identik di kedua platform.** Flutter menggambar UI-nya sendiri, jadi kartu post (teks di atas gambar blur dengan overlay) tampil persis sama di semua device. Ini identitas visual produk.
- **Performa scroll dan animasi stabil** untuk feed panjang dan layar chat.
- **Dart bertipe kuat**, sehingga perbedaan dengan backend Java tidak terlalu jauh.

**Alternatif**
- *React Native (Expo)*: pilihan yang sama baiknya **jika tim sudah menguasai React**. Ekosistemnya besar dan OTA update lebih mudah.
- *Native (Swift + Kotlin)*: performa dan akses platform terbaik, tapi pekerjaan dua kali lipat.

**Trade-off:** ukuran app sedikit lebih besar, dan rekrutmen Flutter sedikit lebih sempit dibanding React.

### Library Flutter

| Library | Kenapa |
|---|---|
| **Riverpod** | State management yang bisa dites tanpa widget, dicek compiler, dan tidak bergantung pada `BuildContext`. Cocok untuk state global seperti status koneksi dan jumlah belum dibaca. |
| **Drift (SQLite)** | Query bertipe yang dicek saat compile, dan **stream reaktif**: saat status pesan berubah (⏱ → ✓ → ✓✓), UI otomatis diperbarui tanpa kode tambahan. SQLite dibutuhkan untuk antrean kirim offline dan cache inbox. |
| **Dio** | Interceptor untuk memasang token dan refresh otomatis saat 401, retry, upload dengan progres. |
| **go_router** | Deep link dari push notification langsung ke layar chat yang tepat. |
| **flutter_secure_storage** | Token disimpan di Keychain/Keystore, bukan di penyimpanan biasa. |
| **firebase_messaging (FCM)** | Satu API untuk Android dan iOS (FCM meneruskan ke APNs). Server cukup mengurus satu integrasi push. |

---

## Tooling

| Alat | Kenapa | Alternatif |
|---|---|---|
| **Maven** (multi-module + `./mvnw`) | Pilihan user, dan sudah dikuasai. Struktur parent POM + modul (`api`, `realtime`, `contracts`) cocok untuk monolith yang nanti dipecah: tiap modul Maven bisa jadi project sendiri. Konfigurasinya deklaratif dan seragam antar project. | Gradle (build inkremental + cache lebih cepat). Selisih kecepatan bisa dikurangi dengan `-pl`/`-am`, `-T 1C` (paralel), dan Maven Build Cache Extension. |
| **Flyway** | Migrasi berupa file SQL biasa yang mudah di-review. Mendukung banyak schema (satu folder per modul). | Liquibase (XML/YAML, lebih verbose) |
| **Testcontainers** | Test memakai Postgres, Redis, Kafka, dan MinIO **sungguhan** di container. Bug yang hanya muncul di DB asli (partisi, constraint, lock) tertangkap sebelum produksi. | H2 in-memory (perilakunya berbeda dari Postgres) |
| **OpenTelemetry** | Standar terbuka, tidak terikat vendor. Satu trace bisa mengikuti pesan dari `msg.send` di gateway → transaksi DB → Kafka → delivery router → socket penerima. | SDK vendor (Datadog, New Relic), yang terkunci ke satu penyedia |
| **k6** | Script JavaScript, **mendukung WebSocket** untuk load test chat, ringan dijalankan dari laptop maupun CI. | Gatling (Scala/Java, lebih berat), JMeter (GUI, dukungan WebSocket lemah) |
| **Kustomize** (manifest k8s, Fase 5) | Bawaan `kubectl apply -k`, tanpa bahasa template; tiga workload dengan beda kecil antar lingkungan cukup di-patch overlay. | Helm (lebih cocok bila banyak parameter/tim), Terraform untuk layanan terkelola di luar cluster. |
| **Docker Compose** (lokal) | Satu perintah menyalakan seluruh dependensi. Semua engineer punya lingkungan yang sama. | — |

---

## Ringkasan: kapan meninjau ulang

| Komponen | Pemicu | Langkah |
|---|---|---|
| Gateway (WebFlux) | Koneksi ±1 juta | Tulis ulang di Go/Elixir. Protokol tetap. |
| Postgres `messages` | > beberapa TB / > 10k tulis/s | Pindahkan `messages` ke ScyllaDB |
| Modular monolith | Modul butuh skala/tim sendiri | Pecah sesuai build guide §3.1 |
| Spring Boot API | Biaya memori dominan | GraalVM native image |
| Flutter | Tim lebih kuat di React | React Native. Backend tidak berubah. |
