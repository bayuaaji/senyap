package app.senyap.safety.internal;

import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Daftar dHash 64-bit materi terlarang yang dikenal (FS-08 hash matching), dimuat dari
 * {@code senyap.safety.image-hash-file}. Cocok = jarak Hamming ≤ {@code image-hash-max-distance}.
 * Di repo file-nya kosong (tanpa data nyata).
 */
@Component
class KnownImageHashes {

    private final long[] hashes;
    private final int maxDistance;

    KnownImageHashes(SafetyProperties props) {
        List<Long> parsed = TermFiles.lines(props.imageHashFile()).stream()
                .map(l -> l.split("\\s+", 2)[0])
                .filter(h -> h.length() == 16)
                .map(h -> HexFormat.fromHexDigitsToLong(h))
                .toList();
        this.hashes = parsed.stream().mapToLong(Long::longValue).toArray();
        this.maxDistance = props.imageHashMaxDistance();
    }

    boolean matches(String hashHex) {
        if (hashHex == null || hashHex.length() != 16 || hashes.length == 0) {
            return false;
        }
        long h = HexFormat.fromHexDigitsToLong(hashHex);
        for (long known : hashes) {
            if (Long.bitCount(h ^ known) <= maxDistance) {
                return true;
            }
        }
        return false;
    }

    int size() {
        return hashes.length;
    }
}
