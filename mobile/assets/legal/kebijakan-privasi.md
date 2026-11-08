# Kebijakan Privasi Senyap

> **DRAF — PERLU DITINJAU KONSULTAN HUKUM SEBELUM RILIS** (termasuk kesesuaian dengan UU 27/2022 tentang
> Pelindungan Data Pribadi dan kewajiban PSE Lingkup Privat). Bukan nasihat hukum.

Terakhir diperbarui: [TANGGAL]

## 1. Prinsip kami: anonim ke sesama pengguna, tidak anonim ke sistem
Pengguna lain **tidak pernah** melihat nomor HP, ID internal, atau profilmu; mereka hanya melihat alias (misalnya "Kelinci Jingga #4821"). Karena alias melekat di semua post, balasan, dan Bisik sampai kamu menggantinya, aktivitas dengan alias yang sama bisa dihubungkan satu sama lain oleh pengguna lain.

Namun Senyap **tidak anonim terhadap sistem**: kami menyimpan identitas akun supaya bisa menindak penyalahgunaan dan memenuhi permintaan hukum yang sah. Kami menyatakannya terbuka di sini.

## 2. Data yang kami kumpulkan
- **Nomor HP**: hanya untuk verifikasi OTP. Yang disimpan adalah HMAC (hash berkunci), bukan nomornya.
- **Data akun**: ID internal, alias dan riwayat alias, status akun, skor kepercayaan, waktu pendaftaran.
- **Perangkat**: platform, token notifikasi.
- **Konten**: post, balasan, pesan Bisik, gambar (metadata EXIF/GPS dihapus).
- **Lokasi kasar** (opsional): sel ±5 km dari perangkat bila kamu mengaktifkannya; koordinat presisi tidak dikirim ke server.
- **Laporan & moderasi**: isi laporan, salinan (snapshot) konten yang dilaporkan, dan tindakan moderasi.

## 3. Enkripsi dan pesan Bisik — mohon dibaca
- Semua koneksi antara aplikasi dan server dienkripsi dengan **TLS**.
- Isi pesan Bisik **tidak dienkripsi end-to-end**. Pesan disimpan di server dalam bentuk yang bisa dibaca sistem.
- Kenapa: aplikasi anonim rentan pelecehan dan konten ilegal. Dengan pilihan ini kami bisa menjalankan moderasi otomatis dan meninjau laporan, termasuk pesan yang sudah ditarik pengirimnya.
- Akses ke isi pesan dibatasi: hanya sistem chat dan moderasi otomatis yang membacanya. Moderator manusia hanya melihat pesan yang ada di **salinan laporan** (pesan yang dilaporkan dan hingga 20 pesan di sekitarnya).
- Isi pesan tidak ditulis ke log atau metrik.
- Kami mengevaluasi enkripsi end-to-end untuk versi mendatang.

## 4. Untuk apa data dipakai
Menjalankan layanan, mencegah dan menindak penyalahgunaan (filter otomatis, antrean moderator, skor kepercayaan), keamanan, dan memenuhi kewajiban hukum. Kami tidak menjual data pribadi.

## 5. Moderator dan permintaan hukum
- Moderator **tidak melihat nomor HP atau ID akun**; akun tampil sebagai kode acak per kasus.
- Penelusuran identitas akun hanya oleh peran legal untuk permintaan hukum yang sah, dengan alasan wajib dan tercatat di audit log.
- Materi eksploitasi seksual anak dilaporkan ke otoritas berwenang.

## 6. Penyimpanan dan penghapusan
- Hapus akun: masa tenggang 29 hari, lalu nomor HP diputus dari akun dan kontenmu ditampilkan sebagai "[dihapus]".
- Salinan laporan dan catatan moderasi dapat disimpan lebih lama untuk keperluan keselamatan dan hukum. [JANGKA WAKTU — DIISI.]

## 7. Hakmu
Sesuai UU PDP: akses, pembaruan, penghapusan, dan keberatan atas pemrosesan. Hubungi [EMAIL PELINDUNGAN DATA / DPO].

## 8. Kontak
[NAMA BADAN HUKUM, ALAMAT, EMAIL]
