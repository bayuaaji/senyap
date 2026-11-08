package app.senyap.safety.internal;

import java.util.Map;

/**
 * Klasifikasi teks dan gambar (FS-08). Penyedia (API moderasi / model ML) <b>belum dipilih</b>; implementasi
 * saat ini {@link HeuristicClassifier} (lokal, heuristik sederhana). Penyedia nyata cukup menjadi bean
 * {@code @Primary} yang mengimplementasikan interface ini; ambang keputusan tetap di {@link ScreeningService}.
 */
interface ModerationClassifier {

    /** Skor 0–1 per label ({@code self_harm}, {@code threat}, {@code sexual}, {@code minor_safety}, {@code hate}, {@code harassment}, {@code spam}). */
    Map<String, Double> classifyText(String text);

    /** Skor 0–1 per label ({@code nudity}). Input berupa sinyal yang dihitung pipeline media. */
    Map<String, Double> classifyImage(ImageSignals signals);

    record ImageSignals(double skinRatio) {
    }
}
