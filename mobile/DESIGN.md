# Senyap: arah desain

Dial: ENERGY 1 / RHYTHM 2 / MOTION 1

Reading this as: app curhat anonim + DM "Bisik" untuk orang dewasa muda Indonesia yang menulis hal yang
tak bisa mereka katakan dengan nama sendiri, dalam bahasa visual **surat tanpa nama**: kertas, tinta,
huruf serif untuk suara manusia, dan satu warna jambu untuk momen berbisik.

## Kenapa arah ini (dan apa yang ditinggalkan)

Versi sebelumnya ("malam yang tenang") memakai tinta biru-hitam, lentera amber, lilac untuk Bisik,
pendar radial, orb bercahaya, bintang, nav kaca melayang, dan pil di semua tempat. Masalahnya:

- Dua aksen (amber + lilac) ditambah warna gender biru/merah muda: terlalu banyak warna, tidak ada yang
  benar-benar menonjol.
- Glow, orb, glassmorphism, dan pil dipakai sebagai dekorasi, bukan sebagai hierarki. Itu ciri AI slop.
- Label huruf kapital berjarak (KEAMANAN, TENTANG CERITA), ikon dalam lingkaran/kotak di setiap baris,
  kartu seragam di setiap bagian: semuanya terasa dicetak dari template.
- Margin 20 dan isi rapat; info penulis dan tombol ditumpuk di atas foto sehingga foto dan tulisan
  berebut perhatian.

Arah baru membuang semua itu dan bertumpu pada tiga hal: **tipografi** sebagai identitas, **ruang kosong**
sebagai struktur, dan **satu aksen** yang hanya muncul di saat berbisik dan memilih.

## Prinsip

1. **Tulisan orang adalah pusatnya.** Setiap layar punya satu fokus. Di feed fokusnya teks cerita di atas
   foto; semua yang lain (penulis, waktu, aksi) duduk di luar foto, kecil dan tenang.
2. **Dua suara.** Serif (Fraunces) = suara manusia: isi cerita, balasan, kutipan, judul layar.
   Sans (Plus Jakarta Sans) = suara app: tombol, label, metadata, percakapan chat. Ini motif identitas
   Senyap dan dipakai konsisten.
3. **Satu aksen, jambu.** Warna jambu hanya untuk: tombol Bisik, gelembung pesanmu, hati yang aktif,
   indikator tab aktif, lencana belum dibaca, tautan. Tombol utama lain memakai tinta (warna teks), bukan
   aksen, supaya Bisik tetap istimewa.
4. **Tanpa dekorasi kosong.** Tidak ada glow, orb, gradien dekoratif, kaca, pola latar, ikon dalam
   lingkaran, garis warna di tepi kiri, atau label kapital berjarak.
5. **Lega.** Gutter 24, jarak antarbagian 32 sampai 40. Ruang kosong memisahkan, bukan garis atau kartu.

## Palet (token `SenyapColors`)

Mengikuti tema sistem. Gelap = "malam" (hitam kehijauan seperti tinta tua), terang = "kertas"
(kertas buram kehijauan, bukan krem). Keduanya setara dan diuji.

| Token | Gelap | Terang | Dipakai untuk |
|---|---|---|---|
| `bg` | `#121413` | `#F1EFE8` | latar layar |
| `surface` | `#1A1D1B` | `#FAF9F5` | sheet, dialog, panel |
| `raised` | `#232724` | `#E6E4DC` | input, gelembung lawan, tombol sekunder |
| `overlay` | `#2D322E` | `#DCD9D0` | pressed, skeleton |
| `line` | `#2F3431` | `#D9D6CC` | garis rambut |
| `text` | `#ECE8DF` | `#191C1A` | teks utama, tombol utama (latar) |
| `textSecondary` | `#B3B5AD` | `#4B504B` | teks pendukung |
| `textTertiary` | `#8C9089` | `#5F645E` | metadata, placeholder |
| `accent` (jambu) | `#F2A08F` | `#A8384F` | Bisik, pilihan aktif, hati |
| `onAccent` | `#2A120C` | `#FFFFFF` | teks di atas aksen |
| `onAccentMuted` | `#5C362D` | `#EED7DC` | jam/status di gelembung sendiri |
| `danger` | `#FF6B63` | `#C42B1C` | aksi merusak (selalu dengan kata kerja jelas) |
| `success` | `#8FCBA6` | `#2F7A4F` | konfirmasi berhasil |

Warna alias (dari server) tetap dipakai sebagai isi lingkaran avatar: itu data identitas, bukan palet UI.
Ikon gender (♂/♀) memakai `textSecondary`, tidak lagi biru/merah muda.

Kontras (dicek dengan `check_contrast`): tertiary di atas raised 4.66 (gelap) / 4.75 (terang);
aksen di atas surface 8.26 (gelap) / 4.93 di atas raised (terang); onAccent di atas aksen 8.57 / 6.28;
onAccentMuted di atas aksen 5.05 / 4.60; danger di atas surface 6.10 / di atas bg 4.92; tinta di atas
tombol utama 15.13. Semua lolos AA teks normal.

Teks di atas foto: foto diredupkan scrim hitam 50% merata + bayangan teks. Ukuran teks cerita minimal 21.
Di foto paling terang (putih), putih di atas scrim = 3.95:1, lolos untuk teks besar; latar galeri dan
foto unggahan juga sudah digelapkan server.

## Tipografi

Keduanya dibundel di `assets/fonts` (OFL), jalan offline dan di test.

- **Fraunces** (serif bervariasi, terasa seperti buku catatan): judul layar, wordmark, isi cerita,
  balasan, kutipan. Dipilih karena cerita anonim lebih mirip surat daripada status.
- **Plus Jakarta Sans** (dirancang Tokotype di Jakarta untuk produk Indonesia, jelas di ukuran kecil):
  semua UI dan chat.

| Peran | Font | Ukuran / tinggi baris / berat |
|---|---|---|
| Judul layar (`display`) | Fraunces | 32 / 1.1 / 500, letter-spacing -0.6 |
| Judul bagian / dialog | Fraunces | 22 / 1.2 / 500 |
| Isi cerita di foto | Fraunces | 21 sampai 30 (menurut panjang) / 1.25 / 500 |
| Balasan, kutipan | Fraunces | 16.5 / 1.5 / 400 (kutipan italic) |
| Judul baris | Jakarta | 16 / 1.3 / 600 |
| Isi UI, chat | Jakarta | 16 / 1.45 / 400 |
| Pendukung | Jakarta | 14 / 1.45 / 400 |
| Metadata | Jakarta | 13 / 1.35 / 500 |
| Label bagian | Jakarta | 13 / 1.3 / 600, huruf biasa (bukan kapital) |

## Spasi

Skala 4: `4, 8, 12, 16, 20, 24, 32, 40, 56, 72`.

- Gutter layar: **24**.
- Feed: dua kolom masonry, margin layar 16, jarak antarkolom 12, jarak antar-post dalam kolom 24.
- Antarbagian di daftar/pengaturan: 32. Label bagian ke isi: 8.
- Tinggi minimum baris yang bisa diketuk: 56. Target ketuk minimum 44.

## Radius

- `sm` 8: lencana kecil, thumbnail kecil.
- `md` 12: input, tombol, chip kategori.
- `lg` 18: foto cerita, gelembung chat, panel.
- `xl` 24: sheet (atas), dialog.
- Lingkaran hanya untuk avatar. Tidak ada tombol atau kartu berbentuk pil.

## Elevasi

Datar. Tidak ada bayangan kecuali bayangan teks di atas foto (keterbacaan). Sheet dan dialog dipisahkan
oleh scrim 55 sampai 60% hitam, bukan bayangan.

## Motion (MOTION 1)

- Tekan: skala 0.98 + haptik ringan di aksi utama (umpan balik bahwa ketukan masuk).
- Perpindahan state: crossfade 180 ms (`Motion.fast`), 240 ms (`Motion.base`), kurva easeOutCubic.
- Transisi halaman: bawaan platform (Cupertino di iOS).
- Tidak ada animasi masuk bertahap, pulsa, atau loop dekoratif. Satu-satunya loop: kilau skeleton saat
  memuat, dan berhenti bila sistem meminta animasi dikurangi.

## Komponen

- **Nav bawah**: docked, lebar penuh, latar `bg` memanjang sampai area home indicator (ikon tetap di atas
  safe area), tanpa margin samping, sudut membulat, atau bayangan; hanya garis rambut di atasnya. Konten tab
  berakhir di atas nav (`bottomNavigationBar` Scaffold), jadi tidak ada yang tertutup. Disembunyikan di chat dan
  saat keyboard terbuka. Aktif = warna teks + ikon terisi; pasif = tertiary. Titik jambu di Pesan bila ada yang
  belum dibaca.
- **Tab teks** (`TextTabs`): kata-kata rata kiri, aktif tebal warna teks dengan garis jambu 2 px di
  bawahnya. Angka permintaan tampil sebagai angka jambu setelah label.
- **Tombol**: utama = tinta (latar `text`, teks `bg`), tinggi 52, radius 12. Aksen = jambu, hanya untuk
  Bisik. Sekunder = `raised` tanpa garis. Teks = tanpa latar.
- **Post di feed** (`PostTile`, dua kolom masonry): foto berisi kategori + potongan teks serif (rasio 1:1,
  4:5, atau 2:3 menurut panjang teks, elipsis bila tak muat) → alias + avatar + ⋯ → kota · waktu → hati,
  balasan, Bisik. Di kartu sempit (layar < 360) Bisik jadi ikon saja. Post dibagi ke kolom yang perkiraan
  tingginya lebih pendek; dua `SliverList` dalam `SliverCrossAxisGroup` sehingga tetap lazy.
- **Post di detail** (`PostCard`): baris penulis → foto 4:5 radius 18 berisi teks serif → baris aksi.
- **Daftar** (pengaturan, blokir, inbox): baris datar di atas `bg`, ikon tanpa wadah, pemisah garis rambut
  yang menjorok. Bukan kotak kartu bertumpuk.
- **Input**: isi `raised`, radius 12, label mengambang di dalam kolom, fokus = garis tinta 1.2.
- **Gelembung chat**: milikmu jambu, lawan `raised`, radius 18 dengan sudut ekor 4.
- **Kosong / gagal / memuat**: judul serif + satu kalimat sebab + satu aksi bila ada. Tanpa ilustrasi atau
  orb. Memuat: skeleton yang mengikuti bentuk isi, atau loader kecil.
- **Avatar**: lingkaran warna alias polos (tanpa gradien/cincin/glow) berisi emoji atau inisial.

## Copy

- Bahasa Indonesia sehari-hari, "kamu", kalimat pendek. Tidak ada em dash; pakai titik, koma, atau titik dua.
- Tombol menyebut aksinya: "Kirim bisikan", "Buat akun", "Buka blokir", bukan "Lanjutkan" generik
  kecuali di langkah berurutan.
- Empty state menyebut kenapa kosong dan apa yang bisa dilakukan.
- Tidak ada klaim yang tidak bisa dibuktikan, tidak ada angka karangan.
- Hal sensitif (blokir, hapus akun, laporan) ditulis lugas dan menyebut akibatnya.
- Tanpa emoji dekoratif di teks UI (emoji avatar adalah data pilihan user).

## Tidak dipakai (dan kenapa)

- Glow, orb, gradien radial, bintang: dekorasi tanpa fungsi (R-01, R-13).
- Glassmorphism di nav dan kartu: meratakan hierarki (R-10).
- Pil di tombol/tab/input: radius jadi dekorasi (R-11).
- Label kapital berjarak: template (R-06).
- Ikon dalam lingkaran/kotak: menambah bentuk tanpa makna (R-14).
- Garis warna di tepi kiri kutipan: dekorasi (diganti kotak garis rambut).
- Warna gender biru/merah muda: stereotip dan menambah warna palet (R-29).
