package app.senyap.shared.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class TsidTest {

    @Test
    void monotonDanUnik() {
        var gen = new Tsid(7);
        long prev = 0;
        var seen = new HashSet<Long>();
        for (int i = 0; i < 100_000; i++) {
            long id = gen.next();
            assertThat(id).isGreaterThan(prev);
            assertThat(seen.add(id)).isTrue();
            prev = id;
        }
    }

    @Test
    void timestampBisaDibacaKembali() {
        long id = new Tsid(1).next();
        assertThat(Duration.between(Tsid.timestampOf(id), Instant.now()).abs()).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void stringRoundTripDanInputBuruk() {
        long id = new Tsid(3).next();
        assertThat(Tsid.parse(Tsid.toString(id))).isEqualTo(id);
        assertThatThrownBy(() -> Tsid.parse("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tsid.parse("-5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Tsid(1024)).isInstanceOf(IllegalArgumentException.class);
    }
}
