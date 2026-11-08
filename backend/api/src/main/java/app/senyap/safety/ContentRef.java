package app.senyap.safety;

/**
 * Rujukan konten. Untuk {@link ContentType#MESSAGE}: {@code id} = ID percakapan, {@code seq} = nomor pesan;
 * selain itu {@code seq} = 0.
 */
public record ContentRef(ContentType type, long id, long seq) {

    public static ContentRef of(ContentType type, long id) {
        return new ContentRef(type, id, 0);
    }
}
