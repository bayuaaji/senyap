package app.senyap.posts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeohashTest {

    @Test
    void encodeDanNormalisasi() {
        assertThat(Geohash.encode(-7.7956, 110.3695, 7)).isEqualTo("qqw7xsb");
        assertThat(Geohash.normalize("QQW7XSB")).isEqualTo("qqw7x");
        assertThat(Geohash.normalize("qqw7")).isNull();
        assertThat(Geohash.normalize("qqw7a")).as("huruf a bukan base32 geohash").isNull();
        assertThat(Geohash.normalize(null)).isNull();
    }

    @Test
    void selDenganDelapanTetangga() {
        var cells = Geohash.withNeighbors("qqw7x");
        assertThat(cells).hasSize(9).startsWith("qqw7x").doesNotHaveDuplicates();
        assertThat(cells).contains(Geohash.encode(-7.80, 110.41, 5)); // ±4 km di timur
        assertThat(cells).doesNotContain(Geohash.encode(-6.2088, 106.8456, 5)); // Jakarta
        cells.forEach(c -> assertThat(c).hasSize(5));
    }

    @Test
    void kotaTerdekat() {
        assertThat(CityLookup.nearest("qqw7x")).isEqualTo("Yogyakarta");
        assertThat(CityLookup.nearest(Geohash.encode(-6.2088, 106.8456, 5))).isEqualTo("Jakarta");
        assertThat(CityLookup.nearest(Geohash.encode(3.5952, 98.6722, 5))).isEqualTo("Medan");
        assertThat(CityLookup.nearest(Geohash.encode(-4.5, 129.5, 5))).as("tengah laut Banda").isNull();
    }

    @Test
    void cuplikanAsalBisik() {
        assertThat(PostsService.excerpt("pendek")).isEqualTo("pendek");
        String panjang = "a".repeat(100);
        assertThat(PostsService.excerpt(panjang)).hasSize(80).endsWith("…");
    }
}
