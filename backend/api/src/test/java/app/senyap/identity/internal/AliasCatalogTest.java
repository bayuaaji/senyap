package app.senyap.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.senyap.identity.Gender;
import app.senyap.identity.internal.AliasCatalog.AliasColor;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AliasCatalogTest {

    /** Hewan yang lazim dipakai sebagai makian; tidak boleh masuk daftar. */
    static final List<String> TERLARANG = List.of("Anjing", "Babi", "Monyet", "Kunyuk", "Kampret", "Jangkrik",
            "Bangsat", "Cecunguk", "Buaya", "Kadal", "Tikus", "Lintah", "Ular", "Kambing", "Keledai", "Kerbau",
            "Kecoa", "Belatung", "Beruk", "Bekantan", "Gorila", "Simpanse", "Orangutan");

    /** Kombinasi yang bermakna negatif/politis dalam Bahasa Indonesia; tidak boleh bisa terbentuk. */
    static final List<String> KOMBINASI_TERLARANG = List.of("Kupu-kupu Malam", "Bunga Malam", "Bintang Kejora",
            "Ayam Kampus", "Putri Malu", "Kumbang Jalang", "Buaya Darat");

    @Test
    void ukuranSesuaiSpesifikasi() {
        assertThat(AliasCatalog.ANIMALS).hasSizeGreaterThanOrEqualTo(200);
        assertThat(AliasCatalog.COLORS).hasSize(20);
        for (Gender g : List.of(Gender.MALE, Gender.FEMALE)) {
            var w = AliasCatalog.words(g);
            assertThat(w.gender()).isEqualTo(g);
            assertThat(w.first()).hasSizeBetween(30, 40);
            assertThat(w.second()).hasSizeBetween(18, 24);
        }
        assertThat(AliasCatalog.words(Gender.UNSPECIFIED).first()).isSameAs(AliasCatalog.ANIMALS);
        assertThat(AliasCatalog.words(null).gender()).isEqualTo(Gender.UNSPECIFIED);
    }

    @Test
    void tanpaDuplikatDanKata1Kata2TidakBeririsan() {
        for (Gender g : Gender.values()) {
            var w = AliasCatalog.words(g);
            var seconds = w.second().stream().map(AliasColor::name).toList();
            assertThat(new HashSet<>(w.first())).as("duplikat kata1 %s", g).hasSameSizeAs(w.first());
            assertThat(new HashSet<>(seconds)).as("duplikat kata2 %s", g).hasSameSizeAs(seconds);
            assertThat(w.first()).as("kata1 ∩ kata2 %s (mis. \"Mawar Mawar\")", g).doesNotContainAnyElementsOf(seconds);
        }
    }

    @Test
    void namaMuatDiKolomDanWarnaHexValid() {
        for (Gender g : Gender.values()) {
            var w = AliasCatalog.words(g);
            for (var first : w.first()) {
                assertThat(first).matches("\\p{Lu}[\\p{L}-]*( [\\p{L}]+)?");
                for (var second : w.second()) {
                    assertThat(AliasCatalog.aliasName(first, second).length()).isLessThanOrEqualTo(40);
                }
            }
            assertThat(w.second()).allSatisfy(c -> {
                assertThat(c.hex()).matches("#[0-9A-F]{6}");
                assertThat(c.name()).matches("\\p{Lu}\\p{Ll}+");
            });
        }
    }

    @Test
    void tanpaHewanYangDipakaiSebagaiMakian() {
        for (Gender g : Gender.values()) {
            var first = AliasCatalog.words(g).first();
            assertThat(first).doesNotContainAnyElementsOf(TERLARANG);
            assertThat(first).noneMatch(a -> TERLARANG.stream().anyMatch(t -> a.startsWith(t + " ")));
        }
    }

    @Test
    void kombinasiBermaknaBurukTidakMungkinTerbentuk() {
        for (Gender g : Gender.values()) {
            var w = AliasCatalog.words(g);
            for (var first : w.first()) {
                for (var second : w.second()) {
                    assertThat(KOMBINASI_TERLARANG).doesNotContain(AliasCatalog.aliasName(first, second));
                }
            }
        }
    }

    @Test
    void contohDariKeputusanUserAdaDiKatalog() {
        var male = AliasCatalog.words(Gender.MALE);
        assertThat(male.first()).contains("Pangeran", "Ksatria", "Pendekar", "Elang", "Naga", "Garuda", "Samudra");
        assertThat(male.second("Senja")).isPresent();
        assertThat(male.second("Jelita")).isEmpty();
        var female = AliasCatalog.words(Gender.FEMALE);
        assertThat(female.first()).contains("Putri", "Dewi", "Bidadari", "Mawar", "Melati", "Rembulan", "Mutiara");
        assertThat(female.second("Jelita")).isPresent();
        assertThat(female.isFirst("Pangeran")).isFalse();
    }
}
