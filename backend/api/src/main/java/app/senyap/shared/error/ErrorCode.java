package app.senyap.shared.error;

import org.springframework.http.HttpStatus;

/** Kode error publik (functional spec §4). Pesan default dalam Bahasa Indonesia. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Data yang dikirim tidak valid."),
    OTP_INVALID(HttpStatus.BAD_REQUEST, "Kode salah atau sudah tidak berlaku."),
    INVALID_EMAIL(HttpStatus.BAD_REQUEST, "Alamat email tidak valid."),
    WEAK_PASSWORD(HttpStatus.BAD_REQUEST, "Kata sandi minimal 8 karakter."),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email ini sudah terdaftar. Silakan masuk."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email atau kata sandi salah."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sesi kamu sudah berakhir. Silakan masuk lagi."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Kamu tidak punya akses ke sini."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Tidak ditemukan."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Metode tidak didukung."),
    ALREADY_WHISPERED(HttpStatus.CONFLICT, "Kamu sudah pernah berbisik dari sini."),
    CONVERSATION_CLOSED(HttpStatus.CONFLICT, "Percakapan ini sudah ditutup."),
    CONVERSATION_PENDING(HttpStatus.CONFLICT, "Tunggu sampai bisikanmu diterima."),
    RETRACT_EXPIRED(HttpStatus.CONFLICT, "Pesan hanya bisa ditarik dalam 10 menit setelah dikirim."),
    CONTENT_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "Konten ini tidak bisa dikirim."),
    ALIAS_CHANGE_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "Alias hanya bisa diganti sekali setiap 7 hari."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Terlalu sering. Coba lagi sebentar lagi."),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Terjadi kesalahan. Coba lagi nanti.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
