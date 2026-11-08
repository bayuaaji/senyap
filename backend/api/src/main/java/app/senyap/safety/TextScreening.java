package app.senyap.safety;

/** Teks yang akan diperiksa. {@code kind} menentukan aturan: pola doxxing hanya untuk POST/REPLY (publik). */
public record TextScreening(ContentType kind, String text) {
}
