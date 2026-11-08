package app.senyap.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.senyap.shared.error.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    void encodeDecodeRoundTrip() {
        var c = Cursor.of(Map.of("score", "12.5", "id", "7251234567890123456"));
        var back = Cursor.decode(c.encode());
        assertThat(back.get("score")).isEqualTo("12.5");
        assertThat(back.getLong("id")).isEqualTo(7251234567890123456L);
    }

    @Test
    void cursorRusakJadiValidationFailed() {
        for (String bad : new String[] {"", "!!!", "YQ", "aWQ9MSZpZD0y" /* id=1&id=2 */}) {
            assertThatThrownBy(() -> Cursor.decode(bad)).isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code()).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void halamanMemakaiBarisEkstraUntukNextCursor() {
        var page = CursorPage.of(List.of(1L, 2L, 3L), 2, id -> Cursor.of("id", Long.toString(id)));
        assertThat(page.items()).containsExactly(1L, 2L);
        assertThat(Cursor.decode(page.nextCursor()).getLong("id")).isEqualTo(2L);

        var last = CursorPage.of(List.of(1L), 2, id -> Cursor.of("id", Long.toString(id)));
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void limitDibatasi() {
        assertThat(CursorPage.clampLimit(null)).isEqualTo(20);
        assertThat(CursorPage.clampLimit(0)).isEqualTo(1);
        assertThat(CursorPage.clampLimit(500)).isEqualTo(50);
    }
}
