package app.senyap.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.senyap.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class EmailAuthServiceTest {

    @Test
    void emailDinormalisasi() {
        assertThat(EmailAuthService.normalize("  Budi.Santoso@Contoh.CO.id ")).contains("budi.santoso@contoh.co.id");
        assertThat(EmailAuthService.normalize("a+tag@b.io")).contains("a+tag@b.io");
    }

    @Test
    void emailTidakValid() {
        for (String bad : new String[] {null, "", "   ", "bukan-email", "a@b", "@contoh.id", "a b@contoh.id",
                "a@contoh..id", "a@@contoh.id", "x".repeat(250) + "@contoh.id"}) {
            assertThat(EmailAuthService.normalize(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void kataSandiMinimal8DanMaksimal72Byte() {
        assertThatCode(() -> EmailAuthService.checkPassword("12345678")).doesNotThrowAnyException();
        assertThatCode(() -> EmailAuthService.checkPassword("x".repeat(72))).doesNotThrowAnyException();
        for (String bad : new String[] {null, "", "1234567", "        ", "x".repeat(73), "é".repeat(40)}) {
            assertThatThrownBy(() -> EmailAuthService.checkPassword(bad)).as(String.valueOf(bad))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("WEAK_PASSWORD"));
        }
    }
}
