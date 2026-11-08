package app.senyap.identity.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Daftar tetap untuk alias {@code {hewan} {warna}} (functional spec §1.1): 200 hewan × 20 warna.
 * Warna nama sekaligus menentukan warna avatar ({@code alias_color}). Hewan yang lazim dipakai sebagai
 * makian dalam Bahasa Indonesia sengaja tidak dimasukkan. Menambah item aman; menghapus atau mengganti
 * item tidak mengubah alias yang sudah tersimpan.
 */
final class AliasCatalog {

    record AliasColor(String name, String hex) {
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

    private static final Map<String, AliasColor> COLORS_BY_NAME =
            COLORS.stream().collect(Collectors.toUnmodifiableMap(AliasColor::name, Function.identity()));

    private AliasCatalog() {
    }

    static boolean isAnimal(String animal) {
        return ANIMALS.contains(animal);
    }

    static Optional<AliasColor> color(String name) {
        return Optional.ofNullable(COLORS_BY_NAME.get(name));
    }

    static String aliasName(String animal, AliasColor color) {
        return animal + " " + color.name();
    }
}
