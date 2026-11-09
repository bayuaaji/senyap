# 01 — System Requirements

Nama kerja produk: **Senyap** (ganti sesuka hati).

> Asal-usul: dokumen ini awalnya diturunkan dari transkrip *Instagram System Design*
> (ByteMonk) di folder ini. Arah produk kemudian berubah jadi **app anonim dengan DM sebagai
> inti** (genre yang sama dengan app semacam Whisper). Yang masih dipakai dari transkrip: pipeline
> gambar, blob store + CDN, distributed queue, counter asinkron, dan cache. Yang dibuang: follow
> graph, fan-out per follower, dan profil publik.

---

## 1. Tujuan produk

Tempat orang bisa **jujur tanpa takut dikenali**:

1. **Post anonim** (*confession*): teks pendek di atas gambar latar, tanpa nama, tanpa profil.
2. **Bisik (DM anonim)** — inti produk: dari sebuah post atau balasan, user bisa memulai
   percakapan privat 1-on-1. Kedua pihak tetap anonim satu sama lain.
3. **Aman**: anonimitas tidak boleh jadi tameng untuk pelecehan. Moderasi dan keselamatan
   adalah requirement inti, bukan tambahan.

## 2. Prinsip anonimitas

| # | Prinsip |
|---|---|
| A1 | User lain **tidak pernah** melihat user ID, nomor HP, username, atau profil. |
| A2 | Setiap akun punya satu **alias** (misalnya "Kelinci Jingga #4821") yang diacak saat profil dibuat. Alias itu melekat dan dipakai di semua balasan dan percakapan DM sampai user mengubahnya sendiri di pengaturan profil. Alias tidak terhubung ke identitas asli. Konsekuensinya, aktivitas yang memakai alias yang sama **bisa** dihubungkan satu sama lain oleh user lain. |
| A3 | Lokasi hanya ditampilkan **kasar** (tingkat kota, atau radius ±5 km). Koordinat presisi tidak pernah disimpan. |
| A4 | Metadata gambar (EXIF, GPS, model kamera) dihapus seluruhnya. |
| A5 | Waktu ditampilkan relatif dengan format "x menit yang lalu", "x jam yang lalu", "x hari yang lalu", "x bulan yang lalu", "x tahun yang lalu", bukan timestamp presisi. Aturan lengkapnya ada di functional spec §4.1. |
| A6 | **Anonim ke sesama user, tidak anonim ke sistem.** Server menyimpan identitas akun (hash nomor HP) supaya bisa menindak penyalahgunaan dan memenuhi permintaan hukum yang sah. Ini dinyatakan jujur di kebijakan privasi. |

## 3. Ruang lingkup

| Masuk MVP (v1) | Fase berikutnya (v2+) | Di luar lingkup |
|---|---|---|
| Daftar via OTP nomor HP, konfirmasi usia 18+, alias acak | Pesan suara & foto sekali-lihat | Halaman profil publik (daftar post per alias), follow |
| Ganti alias di pengaturan profil | | |
| Post anonim (teks + gambar latar) | Grup / ruang obrolan anonim | Iklan |
| Feed: Terbaru, Populer, Sekitar, Kategori | Enkripsi end-to-end (lihat §7) | Video |
| Balasan publik dengan alias | Pesan yang hilang otomatis | Web client |
| Hati (heart) pada post | Search | |
| **DM anonim: request → chat realtime** | Penerjemahan otomatis | |
| Kirim teks & gambar di DM | | |
| Status terkirim / dibaca, indikator mengetik | | |
| Push notification | | |
| Report, block, filter otomatis, konsol moderator | | |
| Hapus akun (purge data) | | |

## 4. Aktor

| Aktor | Keterangan |
|---|---|
| **Guest** | Belum terdaftar. Hanya onboarding. |
| **User** | Terdaftar dan terverifikasi. Post, balas, beri hati, DM. |
| **Moderator** | Meninjau laporan dan antrean otomatis, memberi sanksi. |
| **Admin** | Konfigurasi, kategori, kata terlarang, permintaan hukum. |
| **Sistem** | Worker: pemrosesan gambar, moderasi otomatis, notifikasi, counter. |

## 5. Business requirements

| ID | Requirement |
|---|---|
| BR-01 | User bisa membuat post anonim berisi teks (≤ 250 karakter) di atas gambar latar (dari galeri bawaan atau upload sendiri). |
| BR-02 | User bisa menjelajahi post di feed Terbaru, Populer, Sekitar, dan per Kategori. |
| BR-03 | User bisa membalas post secara publik dengan alias, dan memberi hati. |
| BR-04 | User bisa memulai **DM anonim** ke pembuat post atau penulis balasan. Penerima harus menerima request dulu sebelum chat terbuka. |
| BR-05 | DM berjalan realtime: pesan sampai < 1 detik jika penerima online, push notification jika offline. |
| BR-06 | User tahu status pesannya (terkirim, sampai, dibaca) dan kapan lawan bicara sedang mengetik. |
| BR-07 | User bisa memblokir lawan bicara, keluar dari percakapan, dan melaporkan post, balasan, atau pesan. |
| BR-08 | Konten yang melanggar (pelecehan, ancaman, pornografi, eksploitasi anak, doxxing) disaring otomatis dan/atau ditinjau moderator. |
| BR-09 | Konten yang menyiratkan risiko menyakiti diri sendiri memunculkan informasi bantuan (hotline). |
| BR-10 | User bisa menghapus akun dan seluruh datanya. |

## 6. Non-functional requirements

### 6.1 Target skala (desain untuk 12–18 bulan pertama)

Target 1 miliar user dari transkrip tidak dipakai. Ini angka yang realistis untuk app baru,
dan arsitekturnya tetap bisa dikembangkan melampaui angka ini.

| ID | Metrik | Target |
|---|---|---|
| NFR-01 | User terdaftar | 10 juta |
| NFR-02 | Daily active users | 1 juta |
| NFR-03 | Koneksi WebSocket bersamaan | 200.000 |
| NFR-04 | Pesan DM per hari | 50 juta (rata-rata ~600/s, puncak ~6.000/s) |
| NFR-05 | Post baru per hari | 500.000 |
| NFR-06 | Ukuran maksimum gambar upload | 10 MB (JPEG, PNG, HEIC, WebP). GIF di v2. |

### 6.2 Kualitas

| ID | Atribut | Target |
|---|---|---|
| NFR-10 | Latensi pesan end-to-end (keduanya online) | p95 < 500 ms |
| NFR-11 | Latensi baca feed | p95 < 300 ms |
| NFR-12 | Urutan pesan | Pesan dalam satu percakapan selalu tampil dalam urutan yang sama di kedua sisi. |
| NFR-13 | Pengiriman pesan | *At-least-once* dari jaringan, *exactly-once* di tampilan (deduplikasi lewat `client_msg_id`). |
| NFR-14 | Availability | 99,9% untuk DM, 99,5% untuk feed |
| NFR-15 | Durabilitas pesan | Pesan yang sudah di-ack server tidak boleh hilang (ditulis ke DB sebelum ack). |
| NFR-16 | Keamanan transport | TLS 1.3 untuk semua koneksi client ↔ server dan TLS untuk koneksi internal. Data tersimpan tidak dienkripsi di level aplikasi (lihat §7). Nomor HP hanya disimpan sebagai HMAC (dengan pepper di KMS). **Pengecualian (keputusan 9 Nov 2026):** akun yang daftar dengan email + kata sandi menyimpan email **plaintext** (ternormalisasi) di schema `auth`, karena email dibutuhkan untuk mengirim kode reset kata sandi; kata sandi disimpan sebagai hash BCrypt. Trade-off: kebocoran DB `auth` membuka email (bukan nomor HP). Mitigasi: email tidak pernah ada di respons API, log, event, metrik, atau kunci Redis (rate limit memakai HMAC email), dan dihapus saat akun di-purge. |
| NFR-17 | Pemisahan identitas | API tidak pernah mengembalikan user ID atau nomor HP. User lain hanya mengenal akun lewat alias dan `public_id` miliknya. Alias ditampilkan di post, balasan, dan DM. |
| NFR-18 | Moderasi | Laporan prioritas tinggi (ancaman, eksploitasi anak) ditinjau < 1 jam. Laporan biasa < 24 jam. |
| NFR-19 | Retensi data | Pesan DM disimpan selama percakapan aktif. Dihapus 30 hari setelah kedua pihak menghapus atau keluar. Log moderasi 1 tahun. |
| NFR-20 | Observability | Log terstruktur **tanpa isi pesan**, metrik, dan tracing. |

## 7. Keputusan besar: enkripsi DM

| Opsi | Kelebihan | Kekurangan |
|---|---|---|
| **A. Enkripsi server-side (MVP)** | Moderasi otomatis bisa memindai pesan. Report mudah. Sinkron multi-device sederhana. | Operator secara teknis bisa membaca pesan. |
| B. End-to-end (Signal Protocol) | Privasi maksimal. | Moderasi otomatis tidak mungkin. Report hanya bisa memakai pesan yang dikirim ulang oleh pelapor. Multi-device dan backup jauh lebih rumit. |

**Keputusan (8 Nov 2026): opsi A untuk MVP**, dinyatakan jujur di kebijakan privasi. App anonim
sangat rentan pelecehan dan konten ilegal, jadi kemampuan moderasi lebih penting di awal. E2EE
dievaluasi ulang di v2 dengan model *report-by-forwarding*.

Konsekuensi implementasi:
- Enkripsi **hanya di jalur jaringan**: TLS 1.3 untuk REST dan WebSocket (client ↔ server), dan TLS untuk koneksi internal (app ↔ Postgres, Redis, Kafka, object storage).
- Isi pesan disimpan sebagai **plaintext** di database. Tidak ada enkripsi di level aplikasi.
- Akses ke isi pesan dijaga lewat kontrol akses: hanya modul `chat` dan worker moderasi yang membacanya. Konsol moderator hanya melihat isi pesan lewat snapshot report.
- Isi pesan tidak pernah masuk ke log, event Kafka, atau metrik.

## 8. Kepatuhan (perlu verifikasi hukum)

Item di bawah perlu dicek dengan konsultan hukum sebelum rilis. Ini bukan nasihat hukum.

- Registrasi **PSE Lingkup Privat** di Kominfo/Komdigi (Permenkominfo 5/2020) untuk layanan yang beroperasi di Indonesia.
- **UU PDP** (UU 27/2022): dasar pemrosesan data, hak hapus, notifikasi kebocoran, DPO bila diperlukan.
- Kewajiban takedown konten yang dilarang dan respons permintaan penegak hukum.
- Batas usia (18+) dan kebijakan app store (Apple dan Google mewajibkan fitur report, block, dan moderasi untuk app UGC/anonim).
- Pelaporan materi eksploitasi seksual anak ke otoritas terkait.

## 9. Kriteria penerimaan tingkat sistem

1. User baru bisa daftar via OTP dan membuat post dalam < 2 menit.
2. User lain bisa membuka post itu, mengirim request DM, dan setelah diterima keduanya chat realtime dengan latensi p95 < 500 ms.
3. Tidak ada endpoint yang mengembalikan user ID atau nomor HP (diuji dengan test otomatis).
4. Pesan yang dikirim saat penerima offline sampai lewat push notification, dan muncul berurutan saat app dibuka.
5. Report → muncul di konsol moderator. Block → pihak yang diblokir tidak bisa mengirim pesan lagi.
6. Load test: 50.000 koneksi WebSocket dan 3.000 pesan/s di staging, error < 0,1%.
