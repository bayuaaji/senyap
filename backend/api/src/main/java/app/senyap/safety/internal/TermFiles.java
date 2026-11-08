package app.senyap.safety.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;

/** Baca file daftar (UTF-8, baris kosong dan baris berawalan # diabaikan). */
final class TermFiles {

    private TermFiles() {
    }

    static List<String> lines(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Gagal membaca " + resource.getDescription(), e);
        }
    }
}
