package app.senyap.safety;

/**
 * @param reason {@code KNOWN_HASH} (cocok daftar hash materi terlarang → P0 + suspend, lewat event
 *               {@code media.rejected}), {@code NUDITY}, atau {@code null}
 */
public record ImageVerdict(ScreeningDecision decision, String reason) {

    public static final String KNOWN_HASH = "KNOWN_HASH";
}
