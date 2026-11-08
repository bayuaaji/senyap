# Checklist kepatuhan dan beta tertutup

> Langkah di sini **tidak bisa** dikerjakan dari repo: butuh akun, dokumen hukum, dan keputusan pemilik produk.
> Bukan nasihat hukum — semua poin §8 requirements wajib dicek konsultan hukum. Centang saat selesai.

## 1. Keputusan yang dibutuhkan dari pemilik produk

- [ ] **Penyedia OTP** (SMS/WhatsApp) + kontrak; implementasi `OtpSender` produksi (api gagal start tanpa ini).
- [ ] **Penyedia moderasi** teks dan gambar (ganti `HeuristicClassifier`); DPA karena isi konten dikirim ke pihak ketiga.
- [ ] **Sumber daftar hash** materi eksploitasi anak (mis. kerja sama dengan lembaga resmi) → `SENYAP_SAFETY_IMAGE_HASH_FILE`.
- [ ] **Nomor hotline** kartu bantuan dari sumber resmi, diverifikasi manusia → `senyap.safety.support.*`, `verified: true`.
- [ ] **Penanggung jawab P0**, moderator jaga 24/7, legal: isi `[ISI]` di `docs/05-prosedur-eskalasi-p0.md`.
- [ ] **Konsultan hukum** untuk draf `docs/legal/*` dan poin §2 di bawah.
- [ ] **Akun cloud** (Postgres managed dengan PITR, Redis Cluster, Kafka managed, R2/S3 + CDN, cluster k8s) dan domain.
- [ ] **Identity provider** konsol moderator (Google Workspace / Okta / Keycloak) dengan grup `senyap-moderator`, `senyap-legal`.

## 2. Kepatuhan (requirements §8) — bersama konsultan hukum

- [ ] Registrasi **PSE Lingkup Privat** (Permenkominfo 5/2020) di Komdigi sebelum layanan dibuka untuk publik.
- [ ] **UU PDP** (UU 27/2022): dasar pemrosesan per data (nomor HP sebagai HMAC, konten, lokasi kasar), hak hapus
      (sudah: hapus akun 30 hari), prosedur **notifikasi kebocoran** (3×24 jam ke subjek + lembaga), penilaian perlu
      tidaknya **DPO**, DPIA untuk pemrosesan berisiko tinggi, perjanjian dengan pemroses (OTP, moderasi, cloud).
- [ ] Lokasi penyimpanan data dan transfer lintas negara (region cloud yang dipilih).
- [ ] Prosedur **takedown** konten terlarang + tenggat respons, dan jalur **permintaan penegak hukum** (peran `legal`,
      audit log sudah ada di konsol).
- [ ] **Pelaporan materi eksploitasi anak** ke otoritas: lembaga, format, tenggat (`docs/05-prosedur-eskalasi-p0.md` §4.6).
- [ ] Batas usia 18+ (sudah ada konfirmasi usia saat daftar) + kebijakan bila ditemukan pengguna di bawah umur.
- [ ] Finalisasi Syarat & Ketentuan, Kebijakan Privasi (jelaskan A6 dan keputusan **enkripsi hanya TLS**, isi pesan
      tersimpan di server), Pedoman Komunitas; ganti salinan di `mobile/assets/legal/` dan hapus tanda DRAF.
- [ ] Retensi (NFR-19): pesan 30 hari setelah kedua pihak keluar, log moderasi 1 tahun — konfirmasi sesuai hukum.

## 3. App store (fitur wajib app UGC/anonim sudah ada: report, block, moderasi, kontak dukungan)

Apple App Store:
- [ ] Apple Developer Program (organisasi, D-U-N-S) dan App ID `app.senyap…`; sertifikat + provisioning (fastlane match).
- [ ] App Store Connect: kategori usia **17+/18+**, kuesioner konten UGC, **App Privacy** (nutrition label): nomor HP
      (tidak ditautkan ke identitas publik), konten user, lokasi kasar, ID perangkat untuk push.
- [ ] URL kebijakan privasi + syarat publik; kontak dukungan; cara melapor & memblokir dijelaskan di catatan review
      (Guideline 1.2), akun demo untuk reviewer (OTP mock tidak boleh di produksi: siapkan nomor uji khusus).
- [ ] APNs key untuk push; `NSLocationWhenInUseUsageDescription`/`NSPhotoLibraryUsageDescription` sudah ada.
- [ ] **TestFlight**: grup penguji internal → eksternal (perlu Beta App Review), build dari CI.

Google Play:
- [ ] Akun Play Console (organisasi), signing key (Play App Signing), package name.
- [ ] Data safety form, rating konten (IARC) dewasa, target audience 18+, deklarasi UGC + moderasi.
- [ ] FCM project + `google-services.json`; izin `INTERNET` di manifest release (catatan Fase 0/1).
- [ ] **Internal testing** track → closed testing; Android 14+ butuh ≥ 12 penguji 14 hari sebelum produksi (akun personal).

## 4. Sebelum beta dibuka

- [ ] Staging berjalan dari `infra/k8s/overlays/staging` (rahasia lewat External Secrets), Job `db-bootstrap` sukses.
- [ ] Latihan P0 end-to-end di staging (Fase 4 "selesai jika").
- [ ] Load test penuh di staging (`loadtest/README.md`) + catat hasil.
- [ ] Alarm: P0 tertua > 30 menit, outbox pending naik terus, lag consumer, 5xx, koneksi WS turun drastis.
- [ ] Backup: uji **restore PITR** Postgres ke titik waktu tertentu (bukan sekadar mengaktifkan).
- [ ] Pen-test pihak ketiga (yang otomatis di `ProductionReadinessIT` hanya "ringan").
