package app.senyap.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.senyap.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"081234567890", "81234567890", "6281234567890", "+62 812-3456-7890", "(0812) 3456.7890",
            "006281234567890"})
    void formatIndonesiaDinormalisasiKeE164(String input) {
        assertThat(PhoneNumbers.normalize(input)).isEqualTo("+6281234567890");
    }

    @Test
    void nomorLuarNegeriDiterimaApaAdanya() {
        assertThat(PhoneNumbers.normalize("+14155550123")).isEqualTo("+14155550123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0812", "+62212345678", "+6281234567890123", "+0812345678"})
    void nomorTidakValidDitolakTanpaMenyebutNomornya(String input) {
        assertThatThrownBy(() -> PhoneNumbers.normalize(input))
                .isInstanceOf(ApiException.class)
                .hasMessage("Nomor HP tidak valid.");
    }
}
