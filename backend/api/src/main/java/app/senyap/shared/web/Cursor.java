package app.senyap.shared.web;

import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cursor paginasi yang opaque bagi client: base64url dari {@code k1=v1&k2=v2}. Isinya hanya kunci
 * urutan (misalnya {@code id} atau {@code score} + {@code id}), tidak pernah user ID. Paginasi selalu
 * cursor, tidak pernah offset.
 */
public final class Cursor {

    private static final Pattern KEY = Pattern.compile("[a-z_]{1,20}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final int MAX_LENGTH = 512;

    private final Map<String, String> values;

    private Cursor(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Cursor of(Map<String, String> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("cursor kosong");
        }
        values.forEach((k, v) -> {
            if (!KEY.matcher(k).matches() || !VALUE.matcher(v).matches()) {
                throw new IllegalArgumentException("kunci/nilai cursor tidak valid: " + k);
            }
        });
        return new Cursor(new LinkedHashMap<>(values));
    }

    public static Cursor of(String key, String value) {
        return of(Map.of(key, value));
    }

    public String encode() {
        var sb = new StringBuilder();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        });
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** @throws ApiException {@code VALIDATION_FAILED} jika cursor rusak atau dimanipulasi */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_LENGTH) {
            throw invalid();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            var map = new LinkedHashMap<String, String>();
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                if (eq <= 0 || map.put(part.substring(0, eq), part.substring(eq + 1)) != null) {
                    throw invalid();
                }
            }
            return of(map);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    public String get(String key) {
        String v = values.get(key);
        if (v == null) {
            throw invalid();
        }
        return v;
    }

    public long getLong(String key) {
        try {
            return Long.parseLong(get(key));
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Cursor tidak valid.");
    }
}
