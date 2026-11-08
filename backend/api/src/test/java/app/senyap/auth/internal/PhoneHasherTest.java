package app.senyap.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PhoneHasherTest {

    static AuthProperties props(String pepper) {
        return new AuthProperties(pepper, null, true, null, null, null);
    }

    @Test
    void hmacDeterministikDanBergantungPepper() {
        var a = new PhoneHasher(props("a".repeat(32)));
        var b = new PhoneHasher(props("b".repeat(32)));
        assertThat(a.phone("+6281234567890")).hasSize(32).isEqualTo(a.phone("+6281234567890"));
        assertThat(a.phone("+6281234567890")).isNotEqualTo(b.phone("+6281234567890"));
        assertThat(new String(a.phone("+6281234567890"), StandardCharsets.ISO_8859_1)).doesNotContain("81234567890");
        // Domain terpisah: hash IP tidak pernah sama dengan hash nomor untuk input yang sama
        assertThat(a.ip("+6281234567890")).isNotEqualTo(a.phone("+6281234567890"));
    }

    @Test
    void pepperPendekAtauKosongMenggagalkanStart() {
        assertThatThrownBy(() -> new PhoneHasher(props(null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PhoneHasher(props("pendek"))).isInstanceOf(IllegalStateException.class);
    }
}
