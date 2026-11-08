package app.senyap.shared.error;

import org.springframework.http.HttpStatus;

/** Kode error publik (functional spec §4). Pesan default dalam Bahasa Indonesia. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Data yang dikirim tidak valid."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sesi kamu sudah berakhir. Silakan masuk lagi."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Kamu tidak punya akses ke sini."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Tidak ditemukan."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Metode tidak didukung."),
    ALREADY_WHISPERED(HttpStatus.CONFLICT, "Kamu sudah pernah berbisik dari sini."),
    CONVERSATION_CLOSED(HttpStatus.CONFLICT, "Percakapan ini sudah ditutup."),
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
