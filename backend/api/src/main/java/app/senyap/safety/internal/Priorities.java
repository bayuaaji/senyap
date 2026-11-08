package app.senyap.safety.internal;

import java.util.Collection;
import java.util.Set;

/** Prioritas antrean (FS-07): 0 = P0, 1 = P1, 2 = P2. Alasan report dan label klasifikasi memakai kosakata sama. */
final class Priorities {

    static final Set<String> REASONS = Set.of("harassment", "threat", "sexual", "minor_safety", "self_harm",
            "doxxing", "spam", "hate", "other");

    private Priorities() {
    }

    static int of(String reasonOrLabel) {
        return switch (reasonOrLabel) {
            case "minor_safety", "threat", "self_harm", "known_hash" -> 0;
            case "doxxing", "sexual", "harassment", "nudity" -> 1;
            default -> 2;
        };
    }

    static int highest(Collection<String> labels) {
        return labels.stream().mapToInt(Priorities::of).min().orElse(2);
    }
}
