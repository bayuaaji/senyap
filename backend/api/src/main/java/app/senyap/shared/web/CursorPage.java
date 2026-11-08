package app.senyap.shared.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.function.Function;

/**
 * Bentuk respons daftar: {@code {"items": [...], "next_cursor": "..." | null}}.
 * Ambil {@code limit + 1} baris dari DB lalu panggil {@link #of(List, int, Function)}: baris ekstra
 * hanya dipakai untuk tahu apakah masih ada halaman berikutnya.
 */
public record CursorPage<T>(
        @JsonProperty("items") List<T> items,
        @JsonProperty("next_cursor") String nextCursor) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    public CursorPage {
        items = List.copyOf(items);
    }

    public static <T> CursorPage<T> of(List<T> fetchedLimitPlusOne, int limit, Function<T, Cursor> cursorOf) {
        if (fetchedLimitPlusOne.size() <= limit) {
            return new CursorPage<>(fetchedLimitPlusOne, null);
        }
        List<T> page = fetchedLimitPlusOne.subList(0, limit);
        return new CursorPage<>(page, cursorOf.apply(page.getLast()).encode());
    }

    /** Batasi {@code limit} dari query string ke 1..{@value #MAX_LIMIT}; {@code null} → default. */
    public static int clampLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(requested, 1, MAX_LIMIT);
    }
}
