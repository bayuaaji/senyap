# 02 — Functional Specification

Merinci **apa yang dilakukan sistem** per fitur: aturan, API, protokol realtime, data, dan event.
Requirement tingkat tinggi ada di `01-system-requirements.md`.

Konvensi: `FS-xx` = fitur, `[MVP]` / `[v2]` = fase.

---

## 1. Konsep inti

| Istilah | Arti |
|---|---|
| **Akun** | Identitas internal. Tidak pernah terlihat oleh user lain. |
| **Thread** | Ruang interaksi: sebuah post beserta balasannya, **atau** satu percakapan DM. |
| **Alias** | Nama samaran + tag + warna milik satu akun, dipakai di semua thread. Contoh: "Kelinci Jingga #4821". Diacak saat profil dibuat, berlaku sampai diubah di pengaturan. |
| **Handle publik** | `public_id`: ID opaque acak per akun yang dikirim ke client untuk merujuk "orang ini". Bukan `user_id`. |
| **Bisik** | DM anonim 1-on-1 yang selalu berawal dari sebuah post atau balasan. |

### 1.1 Aturan alias

- **Dibuat sekali saat profil dibuat** (FS-01): kombinasi acak `{hewan} {warna}` (daftar ±200 × 20) + tag 4 digit acak, misalnya "Kelinci Jingga #4821".
- **Unik secara global** pada pasangan `(alias_name, alias_tag)`. Kalau bentrok, acak ulang tag-nya. Tanpa tag, 4.000 kombinasi nama tidak cukup untuk jutaan user.
- **Melekat selamanya** di semua balasan dan percakapan DM sampai user mengubahnya.
- **Bisa diubah di pengaturan profil**: acak ulang, atau pilih sendiri `{hewan}` dan `{warna}` dari daftar (tag tetap diacak sistem). Teks bebas tidak diizinkan, supaya tidak ada nama asli, kontak, atau peniruan.
- Batas ganti: 1× per 7 hari. Mencegah orang yang baru di-report/di-block langsung "berganti wajah".
- Saat alias diubah, **semua balasan dan percakapan lama ikut menampilkan alias baru**, karena alias dibaca dari akun saat render, bukan disalin ke tiap balasan/pesan.
- Riwayat alias disimpan di `alias_history` untuk keperluan moderasi. Tidak pernah ditampilkan ke user lain.
- Alias tampil di **post**, balasan, dan DM. Di balasan post sendiri, pembuat post tampil dengan aliasnya + lencana **Penulis**.
- `public_id` adalah ID acak (UUIDv4) per akun, tidak diturunkan dari `user_id`, dan tidak berubah saat alias diganti.

---

## 2. Model data

```text
users 1──* devices
users 1──* posts 1──* replies
             └──* hearts
users *──* conversations (lewat conversation_members) 1──* messages
users 1──* alias_history
blocks, reports, moderation_actions, notifications
```

**Schema per modul** (aturan M1–M2 di build guide §3.1):

| Schema | Tabel |
|---|---|
| `auth` | devices, refresh_tokens, otp_attempts |
| `identity` | users, alias_history |
| `media` | media |
| `posts` | posts, replies, hearts |
| `chat` | conversations, conversation_members, messages |
| `notifications` | notifications |
| `safety` | blocks, reports, moderation_actions |

Setiap schema juga punya tabel `outbox` sendiri. Tulisan "FK → …" di bawah artinya **referensi
logis**. Constraint FK hanya dipasang jika kedua tabel berada di schema yang sama. Antar schema,
yang disimpan cuma ID-nya, tanpa constraint dan tanpa JOIN.

**users**
| Kolom | Tipe | Aturan |
|---|---|---|
| id | BIGINT (TSID) | PK. Tidak pernah dikirim ke client. |
| phone_hmac | BYTEA | Unik. `HMAC-SHA256(pepper, E.164 phone)`. Nomor asli tidak disimpan. |
| public_id | UUID | Unik. Handle opaque yang dikirim ke client. |
| alias_name | VARCHAR(40) | `{hewan} {warna}`, dari daftar tetap |
| alias_tag | CHAR(4) | 4 digit. Unik bersama `alias_name`. |
| alias_color | CHAR(7) | Warna avatar, hex |
| alias_changed_at | TIMESTAMPTZ | Untuk batas ganti 1× per 7 hari |
| age_confirmed_at | TIMESTAMPTZ | Wajib terisi (18+) |
| status | ENUM | `ACTIVE`, `RESTRICTED` (shadow), `SUSPENDED`, `BANNED`, `DELETED` |
| trust_score | SMALLINT | 0–100, mulai 50 |
| home_geohash | CHAR(5) | Opsional, untuk feed Sekitar (±5 km) |
| created_at | TIMESTAMPTZ | |

**devices**: `id`, `user_id`, `platform` (`ios`/`android`), `push_token`, `last_seen_at`.

**media**: `id`, `owner_id`, `status` (`PENDING_UPLOAD` → `PROCESSING` → `READY`/`REJECTED`),
`original_key`, `mime_type`, `size_bytes`, `width`, `height`, `variants JSONB`, `moderation JSONB`,
`perceptual_hash`. Galeri latar bawaan disimpan di tabel ini dengan `owner_id = NULL`.

**posts**
| Kolom | Tipe | Aturan |
|---|---|---|
| id | BIGINT | PK, berurut waktu. ID publik di-encode sebagai string. |
| author_id | BIGINT | FK → users. Tidak dikirim ke client. |
| body | VARCHAR(250) | Wajib |
| bg_media_id | BIGINT | FK → media |
| category | VARCHAR(30) | `curhat`, `cinta`, `kerja`, `kampus`, `keluarga`, `pengakuan`, `tanya`, `lainnya` |
| geohash | CHAR(5) | Opsional. Hanya diisi jika user mengizinkan lokasi. |
| city | VARCHAR(60) | Hasil reverse-geocode kasar, untuk ditampilkan |
| heart_count, reply_count | INT | Denormalisasi, eventual |
| hot_score | DOUBLE | Untuk feed Populer |
| visibility | ENUM | `PUBLIC`, `HIDDEN` (moderasi), `AUTHOR_ONLY` (shadow-restricted) |
| deleted_at, created_at | TIMESTAMPTZ | |

Index: `(created_at DESC)`, `(category, created_at DESC)`, `(geohash, created_at DESC)`, `(hot_score DESC)`.

**replies**: `id`, `post_id`, `author_id`, `body VARCHAR(500)`, `visibility`, `deleted_at`, `created_at`. Index `(post_id, id)`. Alias tidak disalin ke sini, tapi dibaca dari `users` saat render.

**hearts**: PK `(post_id, user_id)`, `created_at`.

**alias_history**: `user_id`, `alias_name`, `alias_tag`, `valid_from`, `valid_to`. Hanya untuk moderasi.

**conversations**
| Kolom | Tipe | Aturan |
|---|---|---|
| id | BIGINT | PK |
| origin_type, origin_id | ENUM, BIGINT | Post atau balasan tempat Bisik dimulai |
| initiator_id, recipient_id | BIGINT | FK → users |
| status | ENUM | `REQUESTED` → `ACTIVE` → `CLOSED`, atau `REQUESTED` → `DECLINED` |
| last_seq | BIGINT | Nomor urut pesan terakhir |
| last_message_at | TIMESTAMPTZ | Untuk mengurutkan inbox |
| created_at | TIMESTAMPTZ | |

Unik: `(origin_type, origin_id, initiator_id)`, artinya satu Bisik per orang per post atau balasan.

**conversation_members**: PK `(conversation_id, user_id)`, `last_delivered_seq`,
`last_read_seq`, `muted`, `left_at`, `hidden_at` (hapus dari inbox sendiri).

**messages**
| Kolom | Tipe | Aturan |
|---|---|---|
| conversation_id | BIGINT | Bagian dari PK |
| seq | BIGINT | Bagian dari PK. Bertambah 1 per percakapan. |
| sender_id | BIGINT | |
| client_msg_id | UUID | Unik per `(conversation_id, sender_id)`, untuk deduplikasi |
| type | ENUM | `TEXT`, `IMAGE`, `SYSTEM` |
| body | TEXT | ≤ 2.000 karakter, plaintext (enkripsi hanya di jalur jaringan) |
| media_id | BIGINT | Untuk `IMAGE` |
| deleted_for_all_at | TIMESTAMPTZ | "Tarik pesan" (≤ 10 menit setelah kirim) |
| created_at | TIMESTAMPTZ | |

Tabel dipartisi per bulan (`created_at`) supaya retensi dan purge murah.

**blocks**: PK `(blocker_id, blocked_id)`, `conversation_id`, `created_at`.

**reports**: `id`, `reporter_id`, `target_type` (`POST`/`REPLY`/`MESSAGE`/`CONVERSATION`),
`target_id`, `reason`, `note`, `snapshot JSONB` (salinan konten saat dilaporkan), `priority`,
`status` (`OPEN`/`ACTIONED`/`DISMISSED`), `assigned_to`, `created_at`.

**moderation_actions**: `id`, `target_type`, `target_id`, `user_id`, `action`, `reason`, `actor`
(`AUTO`/moderator id), `expires_at`, `created_at`.

**notifications**: `id`, `recipient_id`, `type`, `thread_type`, `thread_id`, `data JSONB`, `read_at`, `created_at`.

---

## 3. Fitur

### FS-01 Onboarding & akun anonim [MVP]

**Alur**
1. Layar pembuka menjelaskan model anonimitas (A1–A6) dalam bahasa sederhana.
2. Konfirmasi usia 18+ dan setuju syarat & kebijakan privasi.
3. Masukkan nomor HP → OTP 6 digit via SMS/WhatsApp (berlaku 5 menit, maksimal 3 percobaan).
4. Server menghitung `phone_hmac`. Kalau sudah ada, akun lama dipakai (login) dengan alias lamanya. Kalau belum, buat akun baru.
5. **Buat profil (akun baru saja):** server mengacak alias + warna. User boleh menekan "Acak lagi" (tanpa batas di langkah ini) atau memilih `{hewan}`/`{warna}` sendiri, lalu "Pakai alias ini".
6. Izin lokasi (opsional) dan izin notifikasi.

**Aturan**
- Satu nomor HP = satu akun. Akun yang `BANNED` tidak bisa dibuat ulang dengan nomor yang sama.
- Nomor HP virtual/VoIP ditolak bila terdeteksi (v2, via penyedia OTP).
- Token: access JWT 15 menit + refresh token 90 hari, dirotasi dan terikat ke device.
- Akun baru (< 24 jam) punya batas lebih ketat (lihat §6).

| Method | Path | Body | Respons |
|---|---|---|---|
| POST | `/v1/auth/otp` | `{phone}` | 202 |
| POST | `/v1/auth/verify` | `{phone, code, device: {platform, push_token}, age_confirmed: true}` | 200 `{tokens, is_new}` |
| POST | `/v1/auth/refresh` | `{refresh_token}` | 200 `{tokens}` |
| POST | `/v1/auth/logout` | | 204 |
| DELETE | `/v1/me` | `{confirm: "HAPUS"}` | 202. Purge dalam 30 hari. |
| PATCH | `/v1/me/settings` | `{location_enabled, home_geohash, notif_*}` | 200 |
| GET | `/v1/me/profile` | | 200 `{public_id, alias: {name, tag, color}, alias_changeable_at}` |
| POST | `/v1/me/alias/suggest` | `{animal?, color?}` (kosong = acak penuh) | 200 `{name, tag, color, suggestion_token}`. Belum disimpan. |
| PUT | `/v1/me/alias` | `{suggestion_token}` | 200 `{alias}`, atau 429 `ALIAS_CHANGE_TOO_SOON`. Batas 7 hari tidak berlaku saat onboarding. |

### FS-02 Post anonim [MVP]

- Body 1–250 karakter. Teks ditampilkan di tengah gambar latar dengan font dan bayangan yang selalu terbaca.
- Gambar latar: pilih dari galeri bawaan (±300 gambar per kategori), **atau** upload sendiri.
- Upload sendiri memakai pipeline yang sama dengan transkrip: presigned PUT → worker (cek magic bytes, strip EXIF, resize ke 1080 px, moderasi gambar, perceptual hash) → `READY`.
- Gambar upload **dikaburkan sedikit (blur 2–4 px) dan diberi overlay gelap**. Ini menjaga teks tetap terbaca dan mengurangi wajah atau detail yang bisa mengenali seseorang.
- Lokasi opsional (per post). Kalau aktif, simpan `geohash` 5 karakter + `city`.
- Teks melewati filter otomatis (FS-08) sebelum terbit. Yang lolos langsung `PUBLIC`.
- Pembuat bisa menghapus post kapan saja. Tidak bisa diedit (supaya balasan tetap masuk akal).

| Method | Path | Respons |
|---|---|---|
| GET | `/v1/backgrounds?category=` | Galeri latar bawaan |
| POST | `/v1/media/uploads` | `{media_id, upload_url}` |
| POST | `/v1/posts` | `{body, category, bg_media_id, use_location}` → 201 `{post}` |
| GET | `/v1/posts/{id}` | Post + `is_mine` |
| DELETE | `/v1/posts/{id}` | 204, hanya pembuat |

### FS-03 Feed [MVP]

Tidak ada follow graph, jadi feed **tidak memakai fan-out per follower** seperti di transkrip.
Semua feed bersifat *pull* dari index yang sama untuk semua orang, lalu di-cache.

| Tab | Isi | Sumber |
|---|---|---|
| **Terbaru** | Semua post publik, terbaru di atas | Index `created_at` |
| **Populer** | Post 72 jam terakhir dengan `hot_score` tertinggi | Redis sorted set `feed:hot`, dihitung ulang tiap 1 menit |
| **Sekitar** | Post dengan geohash di sel user + 8 sel tetangga | Index `geohash`. User harus mengaktifkan lokasi. |
| **Kategori** | Terbaru per kategori | Index `(category, created_at)` |

- `hot_score = log10(1 + hearts + 2·replies) + (created_at_epoch / 45000)`. Ini rumus gaya Reddit: hati dan balasan menaikkan skor, umur menurunkannya.
- Halaman pertama tiap feed publik di-cache 15 detik di Redis (kecuali Sekitar, yang di-cache per sel).
- Disaring per viewer: post milik akun yang diblokir viewer (dua arah), post `HIDDEN`, dan post `AUTHOR_ONLY` milik orang lain.
- 20 item per halaman, paginasi cursor.

| Method | Path |
|---|---|
| GET | `/v1/feed/latest?cursor=` |
| GET | `/v1/feed/popular?cursor=` |
| GET | `/v1/feed/nearby?cursor=` |
| GET | `/v1/feed/category/{slug}?cursor=` |
| GET | `/v1/me/posts?cursor=` (post sendiri, hanya terlihat oleh diri sendiri) |

### FS-04 Balasan & hati [MVP]

- Balasan 1–500 karakter, tampil dengan alias akun penulisnya (§1.1). Balasan pembuat post tampil dengan aliasnya + lencana **Penulis**.
- Urutan: terlama → terbaru, 30 per halaman.
- Hapus balasan: oleh penulis balasan. Pembuat post bisa **menyembunyikan** balasan di post-nya.
- Hati: idempotent (`PUT`/`DELETE`). Counter asinkron dengan batch di Redis, sama seperti like di transkrip.
- **Tidak ada daftar siapa yang memberi hati.** Hanya angka.

| Method | Path | Respons |
|---|---|---|
| GET | `/v1/posts/{id}/replies?cursor=` | `{items: [{id, author: {public_id, name, tag, color, is_post_author}, body, created_at, is_mine}]}` |
| POST | `/v1/posts/{id}/replies` | 201 |
| DELETE | `/v1/replies/{id}` | 204 |
| PUT / DELETE | `/v1/posts/{id}/heart` | 204 |

### FS-05 Bisik: DM anonim [MVP] ★ fitur inti

#### 5.1 Memulai percakapan (request)

1. Di post atau balasan orang lain, user menekan **Bisik**.
2. User menulis pesan pembuka (1–500 karakter, wajib).
3. Server mengecek: bukan diri sendiri, tidak ada block di kedua arah, belum pernah Bisik ke target yang sama dari asal yang sama, kuota harian belum habis, dan penerima tidak mematikan Bisik.
4. Buat `conversation` berstatus `REQUESTED` + pesan pembuka (`seq = 1`). Kedua pihak tampil dengan alias akun masing-masing.
5. Penerima melihatnya di tab **Permintaan**, terpisah dari inbox utama. Isinya alias pengirim, cuplikan post asal, dan pesan pembuka.
6. Penerima memilih:
   - **Terima** → `ACTIVE`. Keduanya bisa chat.
   - **Tolak** → `DECLINED`. Pengirim hanya melihat "Permintaan tidak diterima", tanpa notifikasi tambahan.
   - **Laporkan** → tolak + buat report.
7. Request yang tidak dijawab dalam 7 hari kedaluwarsa (`DECLINED`).

Sebelum diterima, pengirim **tidak bisa** mengirim pesan tambahan. Ini mencegah spam beruntun.

#### 5.2 Chat

| Kemampuan | Aturan |
|---|---|
| Teks | 1–2.000 karakter |
| Gambar | Satu per pesan. Pipeline upload sama, plus moderasi gambar. Ditampilkan blur sampai penerima mengetuk "Lihat" (bisa dimatikan per percakapan setelah 20 pesan bertukar). |
| Status pesan | ⏱ mengirim → ✓ tersimpan di server → ✓✓ sampai ke device penerima → ✓✓ biru dibaca. User bisa mematikan tanda dibaca (berlaku dua arah). |
| Mengetik | Event sementara. Tidak disimpan, berlaku 5 detik. |
| Tarik pesan | Pengirim bisa menghapus untuk semua orang ≤ 10 menit setelah kirim. Diganti "Pesan ditarik". |
| Hapus percakapan | Hanya dari inbox sendiri (`hidden_at`). Muncul lagi jika ada pesan baru. |
| Keluar | `left_at` diisi. Lawan bicara melihat pesan sistem "Lawan bicara telah pergi". Percakapan menjadi `CLOSED` dan tidak bisa dibuka lagi. |
| Block | Seperti keluar, plus pihak yang diblokir tidak bisa Bisik lagi ke pemblokir dari post mana pun, dan konten kedua pihak saling tersembunyi di feed. |
| Bongkar identitas | **Tidak ada fitur tukar identitas.** Kalau user memilih membagikan kontak sendiri lewat teks, sistem menampilkan peringatan sekali per percakapan ("Hati-hati membagikan info pribadi"). |

#### 5.3 Inbox

- Tab **Pesan** (`ACTIVE`, urut `last_message_at`) dan tab **Permintaan** (`REQUESTED` masuk).
- Tiap baris: alias lawan bicara, cuplikan pesan terakhir, waktu relatif, jumlah belum dibaca, dan thumbnail post asal.
- Badge total belum dibaca di ikon tab.

#### 5.4 REST API (operasi non-realtime)

| Method | Path | Respons |
|---|---|---|
| POST | `/v1/conversations` | `{origin_type, origin_id, first_message, client_msg_id}` → 201 `{conversation}` |
| GET | `/v1/conversations?box=inbox\|requests&cursor=` | Daftar percakapan |
| GET | `/v1/conversations/{id}` | Detail + alias kedua pihak + post asal |
| POST | `/v1/conversations/{id}/accept` | 200 |
| POST | `/v1/conversations/{id}/decline` | 204 |
| POST | `/v1/conversations/{id}/leave` | 204 |
| POST | `/v1/conversations/{id}/block` | 204 |
| GET | `/v1/conversations/{id}/messages?before_seq=&limit=50` | Riwayat (sinkronisasi saat app dibuka) |
| GET | `/v1/conversations/{id}/messages?after_seq=` | Ambil pesan yang tertinggal (gap fill) |
| POST | `/v1/conversations/{id}/messages` | Fallback kirim lewat REST jika WebSocket putus |
| DELETE | `/v1/conversations/{id}/messages/{seq}` | Tarik pesan |

#### 5.5 Protokol realtime (WebSocket)

Endpoint: `wss://rt.senyap.app/v1/ws?token=<access_token>`. Semua frame berbentuk JSON:
`{ "t": "<type>", "id": "<frame id>", "d": { … } }`.

**Client → server**
| `t` | `d` | Keterangan |
|---|---|---|
| `msg.send` | `{conversation_id, client_msg_id, type, body, media_id?}` | Kirim pesan |
| `msg.delivered` | `{conversation_id, up_to_seq}` | Device sudah menerima sampai seq ini |
| `msg.read` | `{conversation_id, up_to_seq}` | User sudah membaca sampai seq ini |
| `typing` | `{conversation_id}` | Sedang mengetik (throttle 1 per 3 detik) |
| `ping` | `{}` | Heartbeat tiap 25 detik |

**Server → client**
| `t` | `d` | Keterangan |
|---|---|---|
| `msg.ack` | `{client_msg_id, conversation_id, seq, created_at}` | Pesan sudah tersimpan (✓) |
| `msg.new` | `{conversation_id, seq, sender: "me"\|"them", type, body, media?, created_at}` | Pesan baru |
| `msg.status` | `{conversation_id, delivered_up_to, read_up_to}` | Status di sisi lawan bicara |
| `msg.retracted` | `{conversation_id, seq}` | Pesan ditarik |
| `typing` | `{conversation_id}` | Lawan bicara sedang mengetik |
| `conv.updated` | `{conversation_id, status}` | Diterima, ditolak, ditutup |
| `error` | `{ref_id, code, message}` | Misalnya `RATE_LIMITED`, `CONVERSATION_CLOSED`, `BLOCKED` |
| `pong` | `{}` | |

**Jaminan**
- **Urutan**: server menetapkan `seq` secara atomik (`UPDATE conversations SET last_seq = last_seq + 1 … RETURNING last_seq` dalam transaksi yang sama dengan `INSERT messages`). Client mengurutkan berdasarkan `seq`, bukan waktu.
- **Idempotensi**: client mengirim ulang `msg.send` dengan `client_msg_id` yang sama jika tidak menerima `msg.ack` dalam 5 detik. Server mengembalikan ack yang sama tanpa menyimpan pesan ganda.
- **Gap fill**: kalau client menerima `seq` yang melompat (misalnya 41 lalu 44), client memanggil `GET …/messages?after_seq=41`.
- **Sinkron saat reconnect**: client mengirim daftar `{conversation_id, last_seq}` yang diketahuinya. Server membalas percakapan yang punya pesan lebih baru.
- **Offline**: penerima yang tidak punya koneksi WebSocket aktif → push notification.

### FS-06 Notifikasi [MVP]

| Tipe | Pemicu | Isi push (tanpa isi pesan secara default) |
|---|---|---|
| `DM_REQUEST` | Request Bisik baru | "Seseorang ingin berbisik denganmu" |
| `DM_MESSAGE` | Pesan baru saat offline | "Pesan baru dari Kelinci Jingga". Isi pesan hanya tampil jika user menyalakan pratinjau. |
| `DM_ACCEPTED` | Request diterima | "Bisikanmu diterima" |
| `REPLY` | Balasan di post sendiri | "3 balasan baru di postmu" (digabung per 10 menit) |
| `HEART_MILESTONE` | Hati ke-10, 50, 100, … | "Postmu disukai 50 orang" |

- Push via FCM (Android) dan APNs (iOS). Payload tidak memuat ID internal.
- Pesan DM yang berturut-turut digabung (`collapse_key` per percakapan).

| Method | Path |
|---|---|
| GET | `/v1/notifications?cursor=` |
| POST | `/v1/notifications/read` `{up_to_id}` |

### FS-07 Report & block [MVP]

- Report tersedia di setiap post, balasan, pesan, dan percakapan.
- Alasan: `harassment`, `threat`, `sexual`, `minor_safety`, `self_harm`, `doxxing`, `spam`, `hate`, `other`.
- Saat report dibuat, sistem menyimpan **snapshot** konten (dan untuk DM: 20 pesan di sekitarnya) supaya bukti tetap ada walau pelaku menarik pesannya.
- Prioritas: `minor_safety`, `threat`, `self_harm` → **P0**. `doxxing`, `sexual`, `harassment` → P1. Sisanya P2.
- Pelapor menerima notifikasi status ("Laporanmu sudah ditindaklanjuti") tanpa detail sanksi.
- Block bisa dari percakapan, balasan, atau post. Efeknya dijelaskan di FS-05 §5.2.

| Method | Path |
|---|---|
| POST | `/v1/reports` `{target_type, target_id, reason, note?}` |
| POST | `/v1/blocks` `{target_type, target_id}` (server menentukan akun di baliknya) |
| GET | `/v1/me/blocks` (daftar block, ditampilkan dengan alias & tanggal saja) |
| DELETE | `/v1/me/blocks/{block_id}` |

> Block dan report merujuk **konten** (post/balasan/pesan/percakapan), bukan user ID. Server
> yang menerjemahkannya ke akun, sehingga client tetap tidak pernah tahu user ID.

### FS-08 Moderasi otomatis [MVP]

Dijalankan oleh worker pada setiap post, balasan, pesan teks, dan gambar.

| Lapisan | Konten | Aksi |
|---|---|---|
| Daftar kata & pola | Semua teks | Kata terlarang → tolak dengan pesan ramah. Pola nomor HP / alamat / NIK di **post & balasan publik** → tolak (anti-doxxing). |
| Klasifikasi teks (model ML atau API moderasi) | Post & balasan sebelum terbit. Pesan DM setelah terkirim (asinkron). | Skor tinggi → `HIDDEN` + antrean moderator. Skor sedang → terbit + antrean. |
| Moderasi gambar | Semua upload | Nudity / gore → `REJECTED`. |
| Hash matching gambar | Semua upload | Cocok dengan database hash materi eksploitasi anak yang dikenal → `REJECTED`, akun `SUSPENDED`, laporan P0, prosedur pelaporan ke otoritas. |
| Deteksi risiko menyakiti diri | Post, balasan, DM | Tampilkan kartu bantuan (hotline Indonesia) ke penulis. Tidak memblokir konten. Prioritas tinjau P0. |
| Sinyal perilaku | Akun | Banyak request Bisik ditolak atau banyak di-block → `trust_score` turun → kuota diperketat → `RESTRICTED` (shadow: kontennya hanya terlihat oleh dirinya sendiri). |

### FS-09 Konsol moderator [MVP, web internal]

- Antrean laporan per prioritas, dengan snapshot konten dan konteks.
- Moderator **tidak melihat nomor HP**. Mereka melihat riwayat pelanggaran akun dalam bentuk `account_ref` acak per kasus.
- Aksi: sembunyikan konten, peringatan, batasi (24 jam / 7 hari), suspend, ban. Semua aksi tercatat di `moderation_actions`.
- Akses untuk menelusuri identitas (permintaan hukum) hanya untuk peran `legal`, dengan alasan wajib dan audit log.

### FS-10 Fitur v2 (ringkas)

| Fitur | Catatan |
|---|---|
| Pesan suara | Maks 60 detik, transkrip otomatis untuk moderasi |
| Foto sekali-lihat | Dihapus dari storage setelah dibuka atau setelah 24 jam |
| Pesan hilang otomatis | Timer per percakapan: 24 jam / 7 hari |
| Ruang obrolan anonim | Grup per topik atau per kota, maks 200 anggota |
| E2EE | Lihat keputusan di `01-system-requirements.md` §7 |
| Search | Teks post publik |

---

## 4. Bentuk respons

**Post**
```json
{
  "id": "7251234567890123456",
  "author": { "public_id": "3f9c…", "name": "Kelinci Jingga", "tag": "4821", "color": "#F28C38" },
  "body": "Aku belum bilang ke siapa-siapa kalau aku resign minggu depan.",
  "category": "kerja",
  "background": { "url": "https://cdn.senyap.app/bg/…/1080.webp", "blurhash": "LEHV6nWB2y…" },
  "city": "Yogyakarta",
  "heart_count": 42,
  "reply_count": 7,
  "viewer_has_hearted": false,
  "is_mine": false,
  "can_whisper": true,
  "created_at": "2026-11-08T08:00:00Z"
}
```

- `author` hanya berisi alias dan `public_id`, tidak pernah user ID. `is_mine` dan `can_whisper` dihitung di server.
- `created_at` dibulatkan ke **menit**, supaya tidak bisa dicocokkan dengan aktivitas di luar app secara presisi.
- ID dikirim sebagai string. Paginasi selalu cursor.

### 4.1 Format waktu relatif

Server hanya mengirim `created_at` (dibulatkan ke menit). **Client** yang mengubahnya menjadi
teks, supaya teksnya ikut diperbarui saat layar terbuka lama. Berlaku untuk post, balasan,
pesan DM, inbox, dan notifikasi.

| Selisih dari sekarang | Teks | Contoh |
|---|---|---|
| < 1 menit | baru saja | baru saja |
| 1 – 59 menit | `x menit yang lalu` | 5 menit yang lalu |
| 1 – 23 jam | `x jam yang lalu` | 3 jam yang lalu |
| 1 – 29 hari | `x hari yang lalu` | 12 hari yang lalu |
| 1 – 11 bulan | `x bulan yang lalu` | 4 bulan yang lalu |
| ≥ 12 bulan | `x tahun yang lalu` | 2 tahun yang lalu |

- Angka dibulatkan ke bawah: 1 jam 59 menit tetap "1 jam yang lalu".
- 1 bulan = 30 hari, 1 tahun = 365 hari.
- Angka ditulis sebagai digit ("1 jam yang lalu", bukan "satu jam yang lalu").
- Kalau jam device lebih lambat dari server (selisih negatif), tampilkan "baru saja".
- Pembaruan teks: tiap 1 menit selama selisih < 1 jam, tiap 1 jam setelahnya.

**Error**
```json
{ "error": { "code": "WHISPER_QUOTA_EXCEEDED", "message": "Kamu sudah mencapai batas bisikan hari ini.", "request_id": "…" } }
```

| Kode | HTTP | Kapan |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Input tidak valid |
| `CONTENT_REJECTED` | 422 | Ditolak filter otomatis |
| `UNAUTHENTICATED` | 401 | Token tidak ada atau kedaluwarsa |
| `FORBIDDEN` | 403 | Bukan pemilik resource / akun dibatasi |
| `NOT_FOUND` | 404 | Tidak ada, dihapus, atau disembunyikan karena block |
| `ALREADY_WHISPERED` | 409 | Sudah pernah Bisik dari asal yang sama |
| `ALIAS_CHANGE_TOO_SOON` | 429 | Ganti alias < 7 hari dari perubahan terakhir |
| `CONVERSATION_CLOSED` | 409 | Percakapan sudah ditutup |
| `RATE_LIMITED` / `*_QUOTA_EXCEEDED` | 429 | Dengan header `Retry-After` |

> Kalau target memblokir pengirim, server mengembalikan `NOT_FOUND`, **bukan** `BLOCKED`.
> Dengan begitu pengirim tidak tahu dirinya diblokir.

---

## 5. Event (distributed queue)

Envelope: `{event_id, type, occurred_at, payload}`. Consumer wajib idempotent (`event_id`).
**Isi pesan DM tidak dimasukkan ke event.** Event hanya membawa `conversation_id` dan `seq`,
dan consumer yang butuh isi (moderasi) membacanya dari DB.

| Event | Producer | Consumer | Kunci partisi |
|---|---|---|---|
| `media.uploaded` | Media | Media worker | `media_id` |
| `post.created` / `post.deleted` | Posts | Moderasi, cache feed, counter | `post_id` |
| `reply.created` / `reply.deleted` | Replies | Moderasi, counter, notifikasi | `post_id` |
| `post.hearted` / `post.unhearted` | Hearts | Counter, notifikasi milestone | `post_id` |
| `conversation.requested` / `.accepted` / `.declined` / `.closed` | Chat | Notifikasi, trust score | `conversation_id` |
| `message.created` | Chat | **Delivery router**, push, moderasi | `conversation_id` |
| `message.retracted` | Chat | Delivery router | `conversation_id` |
| `report.created` | Safety | Antrean moderator, trust score | `target_id` |
| `user.blocked` | Safety | Chat (tutup percakapan), cache filter feed | `blocker_id` |

---

## 6. Rate limit & kuota

| Aksi | Akun baru (< 24 jam) | Akun normal | Trust rendah |
|---|---|---|---|
| Request OTP per nomor | 3/jam | — | — |
| Post | 3/hari | 20/hari | 3/hari |
| Balasan | 20/hari | 200/hari | 20/hari |
| **Request Bisik baru** | 3/hari | 20/hari | 0 (dinonaktifkan) |
| Pesan DM | 30/menit | 60/menit | 10/menit |
| Upload gambar | 5/hari | 30/hari | 0 |
| Report | 20/hari | 50/hari | 50/hari |
| Semua REST, per user | 600/menit | | |
