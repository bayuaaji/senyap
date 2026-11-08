package app.senyap.auth.internal;

/**
 * Pengirim + pemeriksa OTP. Implementasi nyata (SMS/WhatsApp) dipilih di Fase 1. Nomor HP tidak
 * pernah ditulis ke log.
 */
interface OtpProvider {

    enum Channel { SMS, WHATSAPP }

    void send(String e164Phone, Channel channel);

    boolean verify(String e164Phone, String code);
}
