package app.senyap.identity.internal;

import app.senyap.identity.Gender;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Daftar tetap untuk alias {@code {kata1} {kata2}} (functional spec §1.1), per gender (keputusan 9 Nov 2026):
 * <ul>
 *   <li>{@code unspecified}: 200 hewan × 20 warna (katalog awal);</li>
 *   <li>{@code male}: tokoh/hewan/alam berkesan gagah × arah, waktu, logam, unsur;</li>
 *   <li>{@code female}: tokoh/bunga/burung/alam berkesan cantik × waktu, warna lembut, kata sifat indah.</li>
 * </ul>
 * Kata kedua sekaligus menentukan warna avatar ({@code alias_color}). Kata pertama dan kedua dalam satu gender
 * tidak beririsan (tidak ada "Mawar Mawar"). Hewan yang lazim dipakai sebagai makian dan kombinasi bermakna
 * negatif (mis. "Kupu-kupu Malam") sengaja tidak mungkin terbentuk (diuji {@code AliasCatalogTest}). Menambah
 * item aman; menghapus atau mengganti item tidak mengubah alias yang sudah tersimpan.
 */
final class AliasCatalog {

    record AliasColor(String name, String hex) {
    }

    /** Katalog satu gender. */
    record Words(Gender gender, List<String> first, List<AliasColor> second) {

        boolean isFirst(String word) {
            return first.contains(word);
        }

        Optional<AliasColor> second(String name) {
            return second.stream().filter(c -> c.name().equals(name)).findFirst();
        }
    }

    static final List<String> ANIMALS = List.of(
            "Kelinci", "Kucing", "Rubah", "Serigala", "Beruang", "Panda", "Koala", "Kanguru", "Walabi", "Wombat",
            "Gajah", "Jerapah", "Zebra", "Singa", "Harimau", "Macan", "Citah", "Jaguar", "Puma", "Rusa", "Kijang",
            "Kancil", "Menjangan", "Muncak", "Unta", "Llama", "Alpaka", "Kuda", "Badak", "Tapir", "Trenggiling",
            "Landak", "Musang", "Luwak", "Tupai", "Bajing", "Marmut", "Hamster", "Kukang", "Tarsius", "Anoa",
            "Banteng", "Bison", "Yak", "Domba", "Okapi", "Antelop", "Impala", "Gazel", "Rakun", "Cerpelai", "Kuskus",
            "Platipus", "Armadilo", "Berang-berang", "Dugong", "Lumba-lumba", "Paus", "Pesut", "Walrus", "Hiu",
            "Pari", "Kakap", "Kerapu", "Tuna", "Salmon", "Gurami", "Arwana", "Koi", "Cupang", "Teri", "Sarden",
            "Belut", "Gurita", "Cumi", "Sotong", "Udang", "Kepiting", "Lobster", "Rajungan", "Kerang", "Siput",
            "Keong", "Ubur-ubur", "Penyu", "Kura-kura", "Bulus", "Komodo", "Biawak", "Iguana", "Bunglon", "Tokek",
            "Cicak", "Katak", "Kodok", "Salamander", "Aksolotl", "Elang", "Rajawali", "Alap-alap", "Celepuk",
            "Merpati", "Perkutut", "Tekukur", "Kakatua", "Nuri", "Beo", "Jalak", "Kenari", "Pipit", "Gelatik",
            "Kutilang", "Murai", "Cucak", "Prenjak", "Kepodang", "Cendrawasih", "Merak", "Kasuari", "Bangau",
            "Kuntul", "Pelikan", "Flamingo", "Angsa", "Bebek", "Itik", "Ayam", "Kalkun", "Puyuh", "Belibis", "Camar",
            "Albatros", "Penguin", "Kolibri", "Rangkong", "Enggang", "Julang", "Gagak", "Walet", "Pelatuk", "Cangak",
            "Kedidi", "Sikatan", "Kupu-kupu", "Capung", "Lebah", "Tawon", "Semut", "Kumbang", "Belalang",
            "Kunang-kunang", "Tonggeret", "Ngengat", "Kepik", "Kelelawar", "Kalong", "Sero", "Garangan", "Binturong",
            "Merbah", "Kacer", "Cililin", "Kangkareng", "Srigunting", "Kipasan", "Gemak", "Ruak", "Mandar", "Trinil",
            "Bubut", "Raja udang", "Cekakak", "Takur", "Paok", "Anis", "Kerak", "Bondol", "Manyar", "Burung madu",
            "Pijantung", "Cabai", "Kacamata", "Sepah", "Kapinis", "Seriti", "Wiwik", "Bentet", "Jingjing", "Tiong",
            "Ekor kipas", "Punai", "Walik", "Delimukan", "Pergam", "Uncal", "Kuau", "Sempidan", "Ayam hutan",
            "Bambangan", "Pecuk");

    static final List<AliasColor> COLORS = List.of(
            new AliasColor("Merah", "#E53935"),
            new AliasColor("Jingga", "#F28C38"),
            new AliasColor("Kuning", "#F2B600"),
            new AliasColor("Hijau", "#43A047"),
            new AliasColor("Biru", "#1E88E5"),
            new AliasColor("Ungu", "#8E24AA"),
            new AliasColor("Nila", "#3F51B5"),
            new AliasColor("Toska", "#00A39A"),
            new AliasColor("Cokelat", "#8D6E63"),
            new AliasColor("Abu", "#78909C"),
            new AliasColor("Perak", "#9EAAB4"),
            new AliasColor("Emas", "#C9A227"),
            new AliasColor("Salem", "#F27B6B"),
            new AliasColor("Marun", "#8B1E3F"),
            new AliasColor("Zaitun", "#7D8B2A"),
            new AliasColor("Lavender", "#9C89D9"),
            new AliasColor("Magenta", "#D81B60"),
            new AliasColor("Tembaga", "#B87333"),
            new AliasColor("Jambon", "#EC6F9F"),
            new AliasColor("Arang", "#37474F"));

    static final List<String> MALE_FIRST = List.of(
            "Pangeran", "Ksatria", "Satria", "Pendekar", "Panglima", "Laksamana", "Pengembara", "Penjelajah",
            "Pujangga", "Pemanah", "Pelaut", "Pendaki", "Elang", "Rajawali", "Serigala", "Harimau", "Singa", "Naga",
            "Garuda", "Macan", "Jaguar", "Beruang", "Badai", "Petir", "Guntur", "Halilintar", "Topan", "Samudra",
            "Komet", "Meteor", "Gerhana", "Karang", "Gunung", "Ombak", "Angin", "Kilat");

    static final List<AliasColor> MALE_SECOND = List.of(
            new AliasColor("Senja", "#E07A3F"),
            new AliasColor("Fajar", "#F29E4C"),
            new AliasColor("Malam", "#2C3E70"),
            new AliasColor("Utara", "#4A90B8"),
            new AliasColor("Timur", "#D35D3A"),
            new AliasColor("Selatan", "#2A9D8F"),
            new AliasColor("Barat", "#8D5A97"),
            new AliasColor("Perak", "#9EAAB4"),
            new AliasColor("Baja", "#5B6B7A"),
            new AliasColor("Emas", "#C9A227"),
            new AliasColor("Hitam", "#263238"),
            new AliasColor("Langit", "#3D8BD9"),
            new AliasColor("Bintang", "#E8B923"),
            new AliasColor("Rimba", "#2E7D32"),
            new AliasColor("Api", "#E53935"),
            new AliasColor("Tembaga", "#B87333"),
            new AliasColor("Kelabu", "#78909C"),
            new AliasColor("Nila", "#3F51B5"),
            new AliasColor("Merah", "#C62828"),
            new AliasColor("Biru", "#1E88E5"));

    static final List<String> FEMALE_FIRST = List.of(
            "Putri", "Dewi", "Bidadari", "Peri", "Ratu", "Puspa", "Kupu-kupu", "Merak", "Angsa", "Kenari",
            "Cendrawasih", "Kolibri", "Merpati", "Mawar", "Melati", "Anggrek", "Teratai", "Sakura", "Tulip", "Dahlia",
            "Kenanga", "Cempaka", "Lili", "Seruni", "Rembulan", "Pelangi", "Embun", "Mutiara", "Permata", "Melodi",
            "Cahaya", "Bunga", "Rinai", "Kilau", "Aurora", "Safir");

    static final List<AliasColor> FEMALE_SECOND = List.of(
            new AliasColor("Senja", "#F28C6B"),
            new AliasColor("Fajar", "#F7A072"),
            new AliasColor("Pagi", "#FFB74D"),
            new AliasColor("Bintang", "#E8B923"),
            new AliasColor("Lavender", "#9C89D9"),
            new AliasColor("Jambon", "#EC6F9F"),
            new AliasColor("Salem", "#F27B6B"),
            new AliasColor("Emas", "#C9A227"),
            new AliasColor("Perak", "#9EAAB4"),
            new AliasColor("Salju", "#90A4AE"),
            new AliasColor("Jelita", "#D16BA5"),
            new AliasColor("Ayu", "#C2185B"),
            new AliasColor("Kirana", "#FF8A65"),
            new AliasColor("Indah", "#AB47BC"),
            new AliasColor("Ungu", "#8E24AA"),
            new AliasColor("Lembayung", "#B5527A"),
            new AliasColor("Toska", "#00A39A"),
            new AliasColor("Pualam", "#A1887F"),
            new AliasColor("Langit", "#64B5F6"),
            new AliasColor("Samudra", "#0277BD"));

    private static final Map<Gender, Words> BY_GENDER = Map.of(
            Gender.UNSPECIFIED, new Words(Gender.UNSPECIFIED, ANIMALS, COLORS),
            Gender.MALE, new Words(Gender.MALE, MALE_FIRST, MALE_SECOND),
            Gender.FEMALE, new Words(Gender.FEMALE, FEMALE_FIRST, FEMALE_SECOND));

    private AliasCatalog() {
    }

    static Words words(Gender gender) {
        return BY_GENDER.get(gender == null ? Gender.UNSPECIFIED : gender);
    }

    static String aliasName(String first, AliasColor second) {
        return first + " " + second.name();
    }
}
