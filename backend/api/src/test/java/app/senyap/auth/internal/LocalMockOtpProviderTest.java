package app.senyap.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalMockOtpProviderTest {

    @Test
    void kodeLokalSelalu000000() {
        var otp = new LocalMockOtpProvider("000000");
        otp.send("+6281200000000", OtpProvider.Channel.WHATSAPP);
        assertThat(otp.verify("+6281200000000", "000000")).isTrue();
        assertThat(otp.verify("+6281200000000", "123456")).isFalse();
    }
}
