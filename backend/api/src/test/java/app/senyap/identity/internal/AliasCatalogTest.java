package app.senyap.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AliasCatalogTest {

    /** Hewan yang lazim dipakai sebagai makian; tidak boleh masuk daftar. */
    static final List<String> TERLARANG = List.of("Anjing", "Babi", "Monyet", "Kunyuk", "Kampret", "Jangkrik",
            "Bangsat", "Cecunguk", "Buaya", "Kadal", "Tikus", "Lintah", "Ular", "Kambing", "Keledai", "Kerbau",
            "Kecoa", "Belatung", "Beruk", "Bekantan", "Gorila", "Simpanse", "Orangutan");

    @Test
    void ukuranSesuaiSpesifikasi() {
        assertThat(AliasCatalog.ANIMALS).hasSizeGreaterThanOrEqualTo(200);
        assertThat(AliasCatalog.COLORS).hasSize(20);
        assertThat(new HashSet<>(AliasCatalog.ANIMALS)).hasSameSizeAs(AliasCatalog.ANIMALS);
    }

    @Test
    void namaMuatDiKolomDanWarnaHexValid() {
        for (var animal : AliasCatalog.ANIMALS) {
            for (var color : AliasCatalog.COLORS) {
                assertThat(AliasCatalog.aliasName(animal, color).length()).isLessThanOrEqualTo(40);
            }
        }
        assertThat(AliasCatalog.COLORS).allSatisfy(c -> assertThat(c.hex()).matches("#[0-9A-F]{6}"));
    }

    @Test
    void tanpaHewanYangDipakaiSebagaiMakian() {
        assertThat(AliasCatalog.ANIMALS).doesNotContainAnyElementsOf(TERLARANG);
        assertThat(AliasCatalog.ANIMALS).noneMatch(a -> TERLARANG.stream().anyMatch(t -> a.startsWith(t + " ")));
    }
}
