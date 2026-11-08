# Progress

Diperbarui di akhir setiap sesi. Sesi baru mulai dari sini.

## Status

| Fase | Status | Catatan |
|---|---|---|
| Dokumen (requirements, spec, guide) | ✅ Selesai | 8 Nov 2026 |
| 0 Fondasi | ✅ Selesai | 8 Nov 2026, commit `bd1c959` |
| 1 Akun anonim | ✅ Selesai | 8 Nov 2026, commit `a2bfb75` |
| 2 Bisik / DM | ✅ Selesai | 9 Nov 2026, digabung ke `main` bersama Fase 3 |
| 3 Post, feed, balasan | ✅ Selesai | 9 Nov 2026, digabung ke `main`; tombol Bisik tersambung |
| 4 Keselamatan | ✅ Selesai | 9 Nov 2026, branch `fase-4-keselamatan` (belum di-merge); penyedia moderasi, hotline, penanggung jawab P0, review hukum menunggu user |
| 5 Siap produksi | 🟡 Kode selesai | 9 Nov 2026, branch `fase-5-produksi`; load test penuh, kepatuhan, beta menunggu staging/user |

Checklist rinci tiap fase ada di `docs/03-build-guide.md` §4. Centang di sana saat item selesai.

## Fase yang sedang berjalan

**Fase 5 — Siap produksi.** Dikerjakan di branch `fase-5-produksi` (dari commit Fase 4), worktree `../senyap-fase-4`.

## Pertanyaan terbuka

- Nomor hotline Indonesia untuk kartu bantuan (config masih `BELUM-DIVERIFIKASI`; api gagal start di produksi sampai diisi + `verified: true`).
- Penanggung jawab P0 + jadwal jaga (`docs/05-prosedur-eskalasi-p0.md`, bagian `[ISI]`). Sumber daftar hash gambar resmi.
- Kepatuhan hukum (PSE, UU PDP) perlu dicek dengan konsultan sebelum rilis.
- Penyedia OTP (SMS/WhatsApp) belum dipilih. Sampai ada implementasi `OtpSender` untuk produksi, api sengaja gagal start di luar profil local/test. API moderasi teks/gambar juga belum dipilih (sementara `HeuristicClassifier`).

## Catatan serah terima

Tulis entri baru di paling atas dengan format ini:

```text
### YYYY-MM-DD — Fase N
- Selesai: …
- Sedang dikerjakan / setengah jadi: … (file, apa yang kurang)
- Masalah / keputusan baru: …
- Langkah berikutnya: …
```

### 2026-11-09 — Fase 5
- Selesai (branch `fase-5-produksi`, dari commit Fase 4 `e89e336`):
  - **Infra sebagai kode** `infra/k8s` (Kustomize, alasan di README + tech decisions): api / worker / realtime (Deployment, Service, HPA, PDB, probe di port management 9464, securityContext), ingress-nginx (API publik hanya `/v1` + `/.well-known`; WS timeout 3600 dtk; konsol `/admin` internal), Job `db-bootstrap` + `base/bootstrap.sql` (role + schema per modul, idempotent, diuji 2× di Postgres sekali pakai), overlay staging/production, rahasia lewat `secretGenerator` dari file di-`.gitignore` (contoh `overlays/*.example.env`). Layanan terkelola (Postgres PITR, Redis Cluster, Kafka SASL, R2 + CDN) lewat env. `backend/Dockerfile`. `kubectl kustomize` kedua overlay OK (19 objek); belum pernah di-apply ke cluster.
  - **Redis Cluster**: kunci yang di-RENAME memakai hash tag `{feed:hot}`, `{posts:heart_delta}` (sebelumnya akan gagal CROSSSLOT).
  - **Drain gateway**: `RealtimeNode.stop()` menandai draining (handshake baru → 1012), menutup sesi 1012 disebar `SENYAP_RT_DRAIN_SPREAD` (10 dtk), tunggu ≤ `SENYAP_RT_DRAIN_TIMEOUT`, bersihkan presence; `spring.lifecycle.timeout-per-shutdown-phase=40s`; k8s preStop 10 dtk + grace 60 dtk. `GatewayDrainTest`. **Bug lama diperbaiki**: tutup 4401 dulu sampai ke client sebagai 1005 (tanpa kode) → `ClientSession.close(status)`. Flutter: 1012 → reconnect 0,2–3 dtk acak.
  - **OpenTelemetry**: `spring-boot-starter-opentelemetry` + `micrometer-registry-prometheus` (api, realtime). `traceparent` disimpan di `EventEnvelope` (outbox) dan header Kafka, dibawa `RealtimeBus.Delivery`; span `msg.send` (gateway, header ke api) → request api → `chat.deliver` (router) → `msg.new` (gateway). Ekspor OTLP mati default (`SENYAP_OTLP_ENABLED`). Metrik: `senyap_rt_connections`, `senyap_rt_send_latency_seconds`, `senyap_chat_delivery_lag_seconds`, `senyap_outbox_pending{module}`/`_oldest_seconds`, `senyap_moderation_queue_open{priority}`/`_oldest_seconds`, lag consumer dari klien Kafka. Dashboard `infra/observability/grafana/dashboards/senyap.json`; profil compose `observability` (collector, Tempo, Prometheus, Grafana) — **belum pernah dinyalakan** (hanya `docker compose config` valid).
  - **`ProductionReadinessIT`** (7): trace ID sama dari request api sampai frame di Redis; meter dashboard terdaftar; log (pesan + MDC + stack trace) tidak memuat isi pesan/balasan, nomor HP (lokal + E.164), kode OTP, access/refresh token; IDOR 13 endpoint `conversations/*` + report/block → 404 identik dengan ID fiktif, `sync` tidak membocorkan; blokir orang lain tidak bisa dibuka, konsol menolak token app, moderator non-legal ditolak; enumerasi alias (tidak ada endpoint lookup, token saran palsu ditolak tanpa bocoran); block vs konten tidak ada: body identik + selisih median waktu < 15 ms.
  - **k6** `loadtest/senyap.js` (chat/idle/feed, profil `small`/`full`, mode seed) + `run-local.sh` (infra terisolasi, port 8380/8382/8390) + README. Hasil laptop: **belum ada** — Docker Desktop macet saat run (container baru berhenti di status `Created`, port 5532 tertahan), k6 tidak sempat jalan. Ulangi `loadtest/run-local.sh` setelah Docker di-restart. Perbaikan skrip dari percobaan: `docker run` tanpa `-i`.
  - `docs/06-checklist-kepatuhan-dan-beta.md` (keputusan user, §8 hukum, App Store/Play, sebelum beta). Build guide, spec (1012, `traceparent`, `{feed:hot}`), CLAUDE.md diperbarui.
- Keputusan baru: Kustomize (bukan Helm); metrik di port management terpisah (`MANAGEMENT_SERVER_PORT=9464`, tidak lewat ingress); `SENYAP_FORWARD_HEADERS=native` di belakang LB (rate limit per IP); trace sampling 10% produksi / 100% staging.
- Verifikasi: `GatewayDrainTest` + `ProductionReadinessIT` (7) lulus; unit api 61 (Modulith + ArchUnit) lulus; `flutter analyze` bersih, `flutter test` 48 lulus. **`./mvnw verify` penuh BELUM dijalankan setelah perubahan Fase 5** (Docker macet) — terutama IT lain setelah ganti kunci Redis `{feed:hot}`/`{posts:heart_delta}` dan dependensi OTel. Jalankan dulu sebelum merge.
- Belum / tidak bisa di sini: load test skala penuh (butuh staging + beberapa generator); apply ke cluster nyata; restore PITR; kepatuhan hukum; TestFlight/Play Console. Test ekspor Prometheus tidak bisa di IT (Spring Boot mematikan ekspor di test) — terbukti di run lokal k6.
- Langkah berikutnya: user review + keputusan di `docs/06-…` §1; merge `fase-4-keselamatan` lalu `fase-5-produksi` ke `main`; siapkan staging.

### 2026-11-09 — Fase 4 (penyelesaian)
- Selesai: Flutter safety di-analyze + diuji; `assets/legal/` terdaftar di pubspec; `settings/legal_screen.dart` (pembaca Markdown sederhana tanpa package) dan `settings/blocks_screen.dart` (daftar + buka blokir) di Pengaturan; `FakeSafetyRepository` di `test/support/fakes.dart`; `test/safety_flow_test.dart` (laporkan → tawaran blokir → post hilang dari feed; blokir → buka blokir di Pengaturan; buka dokumen legal). `docs/05-prosedur-eskalasi-p0.md` (draf, bagian `[ISI]` untuk user). Spec FS-07/§5 diperbarui (`/v1/support/self-harm`, `support_card`, `seq` report MESSAGE, 403 SUSPENDED, event `media.rejected`/`user.sanctioned`). Checklist Fase 4 di build guide dicentang (dengan catatan apa yang masih mock).
- `./mvnw verify` lulus (122 test termasuk `SafetyIT`), `flutter analyze` bersih, `flutter test` 47 lulus.
- Catatan: test `rootBundle.loadString` langsung di `testWidgets` menggantung (bahkan dengan `runAsync`); aset legal diuji lewat layar saja.
- Belum diverifikasi: konsol moderator di staging (belum ada staging); layar safety di simulator.

### 2026-11-09 — Fase 4 (backend, sesi sebelumnya)
- Selesai + diuji: backend Fase 4 lengkap. `./mvnw verify` LULUS (61 unit + 56 IT, termasuk `SafetyIT` 8 skenario: §9 no. 5 report→konsol + block→tidak bisa kirim, P0 end-to-end BAN + notifikasi pelapor + audit + penelusuran legal, filter kata/doxxing, kartu bantuan + antrean P0, HOLD→AUTHOR_ONLY→pulihkan/sembunyikan, shadow restriction + kuota trust rendah, trust turun saat diblokir, hash gambar → REJECTED + SUSPENDED + P0).
  - identity: `adjustTrust`/`applySanction`/`findRecords`, `SanctionExpiryJob`, `identity/V4`. safety: `ContentDirectory` (port; posts/chat mengimplementasi → tanpa siklus), `ScreeningService` + `TextFilter` + `ModerationClassifier`/`HeuristicClassifier` + `KnownImageHashes`, `ReportService`, `BlockService`, `ModerationService` (`ModerationConsoleApi`), `SafetyEventConsumer`, `SafetyController` (`POST /v1/reports`, `POST /v1/blocks`, `GET/DELETE /v1/me/blocks`, `GET /v1/support/self-harm`), `SupportCardGuard` (gagal start di luar local/test bila hotline belum diverifikasi), `safety/V2`. posts: block dua arah di render/detail/balasan, screening sinkron, AUTHOR_ONLY untuk HOLD/RESTRICTED, `authorOnlyPostIds` digabung di feed, `support_card`. chat: screening kata terlarang, SUSPENDED → 403, `support_card` di ack (+ gateway realtime). media: dHash gambar asli + skin ratio, `media.rejected`, kuota upload per tier. admin: Spring Security `/admin/**` (login dev local/test, OIDC opsional lewat `spring.security.oauth2.client.registration.*`, peran dari klaim `groups`: `senyap-moderator`/`senyap-legal`), Thymeleaf: antrean, kasus, aksi, audit, legal.
  - Dokumen: `docs/legal/{syarat-ketentuan,kebijakan-privasi,pedoman-komunitas}.md` (draf, ditandai perlu konsultan hukum), disalin ke `mobile/assets/legal/`.
- Setengah jadi (Flutter, BELUM di-analyze/test): `lib/features/safety/{safety_repository,safety_actions}.dart` (laporkan, blokir, kartu bantuan dengan tanda "PERLU DICEK"), tersambung di post_detail (post + balasan), post_feed_list, compose (support_card), chat_screen (laporkan percakapan, tekan-lama pesan lawan = laporkan, `SupportCardSuggested` dari ack). `chat_engine_test.dart` mungkin perlu disesuaikan (SendAck punya field baru opsional, harusnya aman).
- Belum: daftarkan `assets/legal/` di pubspec + layar baca dokumen legal + daftar blokir di Pengaturan; fake `SafetyRepository` di `test/support/fakes.dart` + widget test laporkan/blokir; `flutter analyze && flutter test` (sekali); `docs/05-prosedur-eskalasi-p0.md` (penanggung jawab = peran yang diisi user); perbarui spec (endpoint/event `media.rejected`, `user.sanctioned`, `report.created`, field `support_card`, `seq` di report MESSAGE, kode 403 SUSPENDED), centang checklist build guide, CLAUDE.md (konsol `http://localhost:8180/admin`, `MAVEN_OPTS=-Djava.net.preferIPv4Stack=true` bila unduh dependensi gagal).
- Keputusan baru: HOLD otomatis = `AUTHOR_ONLY` (penulis tetap melihat) sampai moderator memutuskan; moderator sembunyikan = `HIDDEN`. Satu aksi menyelesaikan semua laporan OPEN atas konten yang sama. Report pesan: `target_id` = ID percakapan + `seq`. Konsol = server-rendered di modul admin (paling sederhana, tanpa SPA). Gambar DM kini juga lewat hash matching (EXIF gambar DM masih belum dibuang — tindak lanjut).
- Butuh keputusan user: penyedia moderasi teks/gambar; nomor hotline (belum diverifikasi, config placeholder `BELUM-DIVERIFIKASI`); penanggung jawab P0; konsultan hukum untuk draf legal; sumber daftar hash resmi.

### 2026-11-09 — Merge Fase 2 + 3
- Selesai: `fase-2-bisik` dan `fase-3-post` digabung ke `main`. Asal Bisik kini lewat adapter `chat/internal/PostsOriginDirectory` → `PostsApi.findOrigins`; `SeedOriginDirectory`, `DevSeedController`, `POST /v1/dev/origins` dihapus, tabel `chat.dev_origins` di-drop (`chat/V3`). Test Bisik membuat post sungguhan lewat `IntegrationTest.createPost`.
- Flutter: `openWhisper` membuka `/bisik/baru?origin_type=…&origin_id=…&excerpt=…` (bukan placeholder lagi).
- `./mvnw verify` lulus (61 unit + 48 IT + realtime/contracts, termasuk `RealtimeE2EIT`); `flutter analyze` bersih, `flutter test` 43 lulus.
- Belum: gambar DM (Fase 2, `DmMediaService`) dan pipeline latar (Fase 3, `MediaService`) masih dua jalur terpisah di modul media — satukan bila perlu. `flutter run` di device belum dicoba.
- Langkah berikutnya: Fase 4 (keselamatan).

### 2026-11-09 — Fase 3
- Selesai: semua item Fase 3 di build guide §4 kecuali sambungan tombol Bisik (menunggu Fase 2). `./mvnw verify` lulus (contracts 2, api 61 unit + 33 IT, realtime 3); `flutter analyze` bersih, `flutter test` 33 lulus. Dikerjakan paralel dengan Fase 2 di worktree terpisah (`fase-3-post`, dari `5a6d692`).
  - **media**: `MediaApi.findBackgrounds` + `Background`. Presigned PUT (JPEG/PNG ≤ 10 MB, content-type + length ditandatangani) → `POST /v1/media/{id}/complete` (HEAD objek, outbox `media.uploaded`) → `MediaWorker` (Kafka consumer + sweeper PROCESSING macet) → `ImagePipeline`: magic bytes, cek dimensi sebelum decode (bom dekompresi), orientasi EXIF, resize 1080, blur 3 px, overlay 35%, JPEG ulang tanpa metadata, BlurHash 4×3, dHash 64-bit → `READY`/`REJECTED`; original dihapus. `GET /v1/media/{id}`, `GET /v1/backgrounds`. `GallerySeeder` (worker) mengisi galeri bawaan. Migrasi `media/V2`. Lokal/test: bucket policy baca publik untuk `public/*` (dipasang app bila `create-bucket=true`).
  - **posts**: `PostsApi` (`findOrigins`, `feedPostIds`, `hotCandidates`, `renderPosts`) + DTO. Post buat/lihat/hapus/`/v1/me/posts`; lokasi = geohash dari device → `Geohash` (5 karakter, 8 tetangga) + `CityLookup` (±75 kota, ≤ 35 km). Balasan (lencana Penulis, sembunyikan oleh pembuat post `PUT/DELETE /v1/replies/{id}/hidden`). Hati idempotent: baris `hearts` sinkron, `heart_count` lewat delta Redis `posts:heart_delta` + flush worker 2 dtk. `PostsWorker`: hot_score tiap 1 menit (jendela 72 jam), consumer `user.purged` (post dihapus, balasan jadi "[dihapus]", idempotent via `consumed_events`). Kuota §6 post/balasan/upload (akun baru < 24 jam dibaca dari TSID user ID). Migrasi `posts/V2`.
  - **feed**: pull tanpa fan-out; cache daftar ID halaman pertama 15 dtk per feed (Sekitar per sel), render per viewer lewat `PostsApi`. Populer = zset `feed:hot` dibangun ulang worker tiap menit (`PopularFeedJob`), cursor (skor, id).
  - **IT**: `PostFeedReplyIT` (skenario A post → B lihat di Terbaru/Sekitar/Kategori/Populer → balas → alias + Penulis → `findOrigins` → hati → sembunyikan → hapus; validasi; kuota; paginasi cursor), `MediaUploadIT` (upload → READY → dipakai post; file palsu REJECTED; kuota). Unit: `GeohashTest`, `ImagePipelineTest`.
  - **mobile**: `features/posts` (model, `PostsRepository` Dio termasuk upload presigned + polling, `PostCard` teks di atas latar + `BlurHashBox` dekoder sendiri, `PostFeedList` cursor + tarik muat ulang, `PostDetailScreen` balasan + lencana Penulis + sembunyikan/hapus), `feed_screen` (tab Terbaru/Populer/Sekitar/Kategori; lokasi hanya diminta saat diketuk), `compose_screen` (editor + pratinjau, kategori, galeri latar, upload foto, toggle kota), `core/time/relative_time.dart` (§4.1, diperbarui tiap menit/jam), `core/geo/geohash.dart`, `whisper/whisper_entry.dart` (placeholder Bisik). Route `/post/:id`. Package baru: `image_picker`, `geolocator` (+ izin `NSLocationWhenInUseUsageDescription`, `NSPhotoLibraryUsageDescription`, `ACCESS_COARSE_LOCATION`).
- Sedang dikerjakan / setengah jadi: tombol Bisik membuka placeholder (`mobile/lib/features/whisper/whisper_entry.dart`, `openWhisper`).
- **Sambungan saat merge Fase 2:**
  - Modul chat memanggil `PostsApi.findOrigins(List<OriginRef>)` (`OriginType` POST/REPLY = nilai `chat.conversations.origin_type`); asal yang tidak ada di hasil → `NOT_FOUND`; tolak Bisik ke diri sendiri (`ownerUserId == initiator`); block dicek Fase 4. `WhisperOrigin.excerpt` (≤ 80 karakter) untuk konteks layar request.
  - Flutter: ganti isi `openWhisper(context, type, originId)` dengan navigasi ke layar request Bisik. Tombol muncul bila `can_whisper` (post dan balasan).
  - File bersama yang diubah fase ini (semua menambah): `backend/api/src/main/resources/application.yml` (`senyap.media.public-base-url`, `gallery-seed`, `gallery-per-category`), `application-test.yml`, `support/IntegrationTest.java` (+1 baris), `mobile/lib/app/router.dart` (+route `/post/:id`), `mobile/pubspec.yaml`/`pubspec.lock` (+2 package), `test/support/fakes.dart` (+override repo post/lokasi/picker), Info.plist, AndroidManifest. Tidak menyentuh chat/notifications/realtime/`ErrorCode`/contracts.
- Masalah / keputusan baru:
  - **Pipeline gambar Java murni (ImageIO), bukan libvips**: tanpa library native di container; volume upload MVP kecil. Input hanya JPEG/PNG (image_picker meng-encode ulang HEIC → JPEG). Ganti `ImagePipeline` bila CPU worker jadi bottleneck.
  - **Galeri bawaan = placeholder generatif** (`GalleryArt`, gradien + lingkaran, deterministik). **Aset final berlisensi (±300/kategori) belum disiapkan**; matikan `senyap.media.gallery-seed` setelah diimpor.
  - **Lokasi**: server hanya menerima geohash dari device, tidak pernah lat/lng; `geohash` tidak dikirim balik. Feed Sekitar memakai query `cell` (sel ±5 km ikut tercatat di access log — terima, atau pindah ke header di Fase 5).
  - Urutan + cursor feed memakai ID TSID (setara `created_at`), index parsial baru di `posts/V2`.
  - Kuota "trust rendah" (§6) belum diterapkan: butuh trust score (Fase 4).
  - Balasan yang disembunyikan pembuat post: kolom terpisah `hidden_by_post_author_at` (bukan `visibility`) supaya tidak tertukar dengan tindakan moderator; tidak dihitung di `reply_count`.
  - Endpoint/kode baru dicatat di functional spec FS-02/03/04 + §4.
- Belum diverifikasi: `flutter run` di simulator/device (termasuk izin lokasi/foto, upload presigned dari device ke MinIO lokal — emulator Android perlu `SENYAP_MEDIA_PUBLIC_URL`/endpoint S3 `10.0.2.2`, bukan `localhost`); belum dicoba manual dengan `spring-boot:run` (port Fase 3 bila paralel: 8280/8282).
- Lubang yang diketahui (tindak lanjut): filter blokir di feed + filter teks FS-08 (Fase 4); media `PENDING_UPLOAD` yang ditinggal belum dibersihkan; delta hati di Redis hilang bila worker mati di antara RENAME dan commit (tanpa rekonsiliasi); post `AUTHOR_ONLY` milik viewer tidak muncul di feed bersama (hanya di `/v1/me/posts`) — tinjau saat shadow-restrict Fase 4; tombol Bisik.
- Langkah berikutnya: user review + commit `fase-3-post`, merge dengan Fase 2, sambungkan `openWhisper` + `findOrigins`, lalu skenario penuh "B membuka Bisik ke A dari balasan, A menerima".

### 2026-11-09 — Fase 2
- Selesai: semua item Fase 2 di build guide §4. `./mvnw verify` lulus (contracts 2, realtime 3, api 52 unit + 41 IT); `flutter analyze` bersih, `flutter test` 21 lulus. Belum di-commit (branch `fase-2-bisik`, worktree `../senyap-fase-2`).
  - "Selesai jika" dibuktikan `RealtimeE2EIT`: api + gateway realtime **sebagai proses terpisah** (jar) + dua klien WebSocket nyata. Dua klien chat realtime (latensi lokal kirim→terima **p50 14 ms, p95 17 ms**, 50 pesan); 5 pesan dengan ack hilang dikirim ulang (`client_msg_id` sama, dua putaran) → tersimpan sekali, seq 2–6 berurutan, penerima mendapat tepat 5 `msg.new`; gateway di-restart → 3 pesan fallback REST saat mati diambil lewat `/sync` + `after_seq`, realtime jalan lagi; penerima offline → 2 push mock `DM_MESSAGE` (collapse per percakapan, tanpa isi) lalu riwayat lengkap.
  - **chat**: `ChatService` (request: validasi asal, self, block dua arah → `NOT_FOUND`, `ALREADY_WHISPERED`, kuota 3/20/0 per hari; accept/decline/expire 7 hari; kirim: dedup `message_dedup` → `UPDATE last_seq … RETURNING` → insert → outbox dalam satu `chatTx`, rate 30/60/10 per menit; tarik ≤ 10 menit; keluar/block → pesan SYSTEM `left` + `CLOSED`; hide; receipts → `msg.status` via Redis), `ChatQueries` (inbox/requests cursor, detail, riwayat, gap fill, sync, badge), `ChatController`, `DeliveryRouter` (Kafka `chat.events` + `safety.events`, idempotent lewat `chat.consumed_events`), `RealtimePublisher`, `ChatOutboxPump` (relay chat serial, kick setelah commit, poll 100 ms; menggantikan relay chat di scheduler bersama 500 ms), `RequestExpiryJob`. Migrasi `chat/V2`.
  - **Port asal Bisik** `chat/OriginDirectory`; implementasi SEMENTARA `SeedOriginDirectory` (tabel `chat.dev_origins`) + `POST /v1/dev/origins` (local/test).
  - **identity** `findStandings` + `AccountStanding`; **auth** `DeviceApi` (push target device yang masih punya sesi); **safety** `SafetyApi` + `BlockService` (minimal, event `user.blocked`); **notifications** `NotificationsApi` + `PushService` + `LoggingPushSender` (mock); **media** gambar DM (`DmMediaApi`, `/v1/media/dm-uploads`, objek privat `dm/`, presigned GET 1 jam). Kode error `CONVERSATION_PENDING`, `RETRACT_EXPIRED`.
  - **realtime**: `RealtimeNode` (sesi lokal, presence Redis, channel `rt:node:{node}`, refresh 20 dtk, idle 75 dtk, hapus presence saat shutdown), `ChatApiClient` (meneruskan `msg.send`/receipts ke REST api dengan token user), handler (frame per sesi berurutan, typing throttle 3 dtk langsung ke node lawan, 401 → tutup 4401). **contracts** `rt/RealtimeBus`, `FrameType.CLOSE_UNAUTHENTICATED`.
  - **mobile**: drift (SQL langsung, tanpa codegen) + drift_flutter + image_picker. `core/realtime/realtime_client.dart` (dart:io WebSocket, Bearer, ping 25 dtk, backoff 1→30 dtk ±20%, 4401 → refresh), `features/messages/` (`ChatStore` antrean + salinan pesan + status lawan, `ChatEngine` kirim berurutan + kirim ulang id sama + fallback REST + gap fill + sync saat reconnect, layar inbox/Permintaan, chat dengan ⏱/✓/✓✓/biru, terima/tolak, tarik, keluar, blokir, gambar blur sampai "Lihat", peringatan berbagi kontak sekali per percakapan, layar `/bisik/baru?origin_type=&origin_id=`). Data chat lokal dihapus saat keluar akun.
- Masalah / keputusan baru:
  - **Routing pakai `public_id`**: presence + node channel dikunci `public_id`; delivery router (worker api) menerjemahkan user ID → `public_id` lewat `IdentityApi` (cache). Gateway tetap tidak pernah tahu user ID dan tidak punya akses DB.
  - **Gateway → api lewat REST dengan token user** (bukan API internal baru): validasi, rate limit, dan penulisan satu jalur dengan fallback REST.
  - Bug Fase 1 ditemukan: atribut principal dari `HandshakeAuthFilter` tidak tersalin ke sesi WS. Handler kini membaca + memverifikasi ulang token dari header handshake.
  - Frame ke node yang mati (gateway crash, presence masih ≤ 60 dtk) hilang dan tidak memicu push; ditutup oleh sync `last_seq` saat client reconnect. Gateway yang berhenti normal menghapus presence-nya dulu.
  - Pesan pembuka (seq 1) tidak menerbitkan `message.created`; push request lewat `conversation.requested`. Isi pesan ditarik tetap di DB (bukti report), tidak pernah dikirim lagi.
  - `msg.new` juga dikirim ke device lain milik pengirim (`sender: me`). Status dibaca selalu dua arah; opsi mematikan tanda dibaca dan "penerima mematikan Bisik" belum ada (belum ada pengaturan user).
  - Urutan modul Maven: `realtime` sebelum `api` (IT e2e menjalankan jar gateway; dilewati bila jar belum ada).
  - Sinkron saat reconnect lewat REST `POST /v1/conversations/sync`, bukan frame WS.
  - Spec diperbarui (endpoint, bentuk respons, kode error, catatan implementasi WS, payload event, push).
- Belum diverifikasi: `flutter run` di simulator/device (Xcode/Android SDK belum siap) — termasuk drift_flutter/sqlite di device, image_picker, WebSocket dari emulator (`--dart-define=SENYAP_RT_URL=ws://10.0.2.2:8190/v1/ws`). FCM nyata + registrasi push token di app belum ada. Manifest Android release belum punya izin INTERNET (dari Fase 0/1).
- Catatan merge dengan Fase 3 (lihat juga `fase-2-merge-notes.md` di folder catatan sesi): adapter `PostsApi.findOrigins` → `OriginDirectory` (`@Primary`), hapus `SeedOriginDirectory`/`DevSeedController`/`dev_origins`; arahkan `openWhisper` Fase 3 ke `/bisik/baru?origin_type=…&origin_id=…&excerpt=…`; ganti `chat_time.dart` dengan `core/time/relative_time.dart`; satukan gambar DM dengan pipeline media Fase 3 dan pastikan `findBackgrounds` menolak media `dm/`.
- Langkah berikutnya: review + commit Fase 2, merge dengan Fase 3, lalu Fase 4.

### 2026-11-08 — Fase 1 (lanjutan)
- Selesai: keputusan user untuk pertanyaan terbuka hapus akun = **dibatalkan**. Login ulang (OTP verify) sebelum purge membatalkan penghapusan: status kembali ke status sebelum dihapus, `deleted_at` dikosongkan (job purge tidak memprosesnya), event `user.restored`, `public_id` + alias tetap. Setelah purge, nomor yang sama = akun baru. Migrasi `identity/V3` (`deleted_from_status`). IT di `AccountLifecycleIT`. Teks konfirmasi hapus akun di Flutter menyebut pembatalan.
- Masalah / keputusan baru: status dipulihkan ke status **sebelum** dihapus (bukan selalu `ACTIVE`), supaya `RESTRICTED`/`SUSPENDED` tidak bisa dihapus dengan cara hapus akun lalu login lagi. Akun `BANNED` tidak bisa dihapus (memang tidak bisa login).
- Langkah berikutnya: Fase 2 (Bisik/DM, FS-05).

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
