package app.senyap.safety;

import java.util.List;

/**
 * @param message  pesan ramah untuk user bila {@code REJECT}
 * @param selfHarm penulis perlu ditawari kartu bantuan (konten tidak diblokir karena ini)
 * @param labels   label klasifikasi, mis. {@code threat}, {@code sexual}, {@code self_harm}
 * @param priority prioritas antrean bila FLAG/HOLD: 0 = P0, 1 = P1, 2 = P2
 */
public record TextVerdict(ScreeningDecision decision, String message, boolean selfHarm, List<String> labels,
                          int priority) {

    public boolean queued() {
        return decision == ScreeningDecision.FLAG || decision == ScreeningDecision.HOLD;
    }
}
