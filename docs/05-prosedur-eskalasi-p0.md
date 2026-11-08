# Prosedur Eskalasi P0

> **DRAF — wajib diisi dan disetujui sebelum beta.** Bagian bertanda `[ISI]` harus diisi oleh user/pemilik
> produk. Prosedur pelaporan ke otoritas perlu dicek konsultan hukum (requirements §8). Ini bukan nasihat hukum.

## 1. Apa itu P0

Laporan atau deteksi otomatis yang menyangkut keselamatan nyawa atau anak. Sumber (lihat `safety/internal/Priorities`):

| Pemicu | Sumber |
|---|---|
| `minor_safety` — konten yang membahayakan / menyeksualisasi anak | report user, klasifikasi teks |
| `threat` — ancaman kekerasan yang nyata | report user, klasifikasi teks |
| `self_harm` — risiko menyakiti diri | report user, deteksi otomatis (kartu bantuan sudah tampil ke penulis) |
| `known_hash` — gambar cocok dengan daftar hash materi eksploitasi anak | `KnownImageHashes` saat upload: gambar `REJECTED`, akun otomatis `SUSPENDED` |

## 2. Peran dan penanggung jawab

| Peran | Tugas | Orang | Kontak cadangan |
|---|---|---|---|
| Moderator jaga (peran `senyap-moderator`) | Memantau antrean P0, triase dalam SLA | `[ISI]` | `[ISI]` |
| Penanggung jawab P0 / Trust & Safety lead | Keputusan sanksi berat, eskalasi ke otoritas | `[ISI]` | `[ISI]` |
| Legal (peran `senyap-legal`) | Penelusuran identitas, respons permintaan penegak hukum, pelaporan ke otoritas | `[ISI]` | `[ISI]` |
| Engineer on-call | Pemeliharaan bukti, takedown teknis, insiden sistem | `[ISI]` | `[ISI]` |

Jadwal jaga (rotasi, zona waktu WIB): `[ISI]`. Kanal darurat internal: `[ISI]`.

## 3. Target waktu (SLA)

| Langkah | Target |
|---|---|
| P0 masuk → ditinjau moderator | ≤ 1 jam, 24/7 |
| `known_hash` → akun suspended | otomatis, instan |
| Ancaman nyata / anak dalam bahaya → penanggung jawab P0 diberi tahu | ≤ 15 menit setelah ditinjau |
| Laporan ke otoritas (bila wajib) | sesuai ketentuan hukum `[ISI setelah konsultasi]` |

Alarm: antrean P0 yang lebih tua dari 30 menit memicu notifikasi ke moderator jaga (metrik
`senyap_moderation_queue_oldest_seconds{priority="0"}`, lihat dashboard Fase 5).

## 4. Langkah per kasus

1. **Buka kasus** di konsol `/admin` (antrean P0 di atas). Moderator melihat snapshot konten, konteks (DM: 20 pesan
   sekitar), dan riwayat pelanggaran lewat `account_ref` acak — **bukan** nomor HP.
2. **Amankan bukti.** Jangan hapus konten secara permanen; aksi "sembunyikan" mempertahankan snapshot. Snapshot
   report tetap ada walaupun pelaku menarik pesan.
3. **Tindak:**
   - `minor_safety` / `known_hash` → **ban**, konten disembunyikan, eskalasi ke penanggung jawab P0 dan legal.
   - `threat` yang nyata dan spesifik → suspend/ban, eskalasi ke penanggung jawab P0; legal menilai pelaporan ke polisi.
   - `self_harm` → tidak menghukum penulis. Pastikan kartu bantuan tampil; sembunyikan hanya bila berisi instruksi
     berbahaya. Bila ada indikasi bahaya segera dan ada permintaan resmi, ikuti jalur legal.
4. **Satu aksi menyelesaikan** semua laporan OPEN atas konten yang sama; pelapor menerima notifikasi tanpa detail sanksi.
5. **Penelusuran identitas** (hanya peran legal, alasan wajib, tercatat di audit log) dilakukan bila ada permintaan
   hukum yang sah atau kewajiban pelaporan.
6. **Pelaporan ke otoritas** untuk materi eksploitasi anak: lembaga tujuan, format, dan tenggat `[ISI setelah konsultasi
   hukum]`. Simpan nomor tiket/bukti pengiriman di catatan kasus.
7. **Tutup kasus** dengan catatan; audit log mencatat siapa, kapan, aksi apa.

## 5. Hal yang tidak boleh dilakukan

- Mengunduh atau meneruskan materi eksploitasi anak ke luar sistem (termasuk screenshot ke chat internal).
- Menghubungi user di luar app atau membuka identitas tanpa dasar hukum dan tanpa peran legal.
- Menyalin isi pesan, nomor HP, atau token ke log, tiket, atau kanal umum.

## 6. Latihan

Uji prosedur ini end-to-end di staging sebelum beta (kriteria "Selesai jika" Fase 4): buat laporan `minor_safety`,
proses sampai ban + notifikasi pelapor + audit, lalu cek waktu tiap langkah. Ulangi tiap kuartal. Tanggal latihan
terakhir: `[ISI]`.
