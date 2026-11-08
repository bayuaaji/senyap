package app.senyap.posts.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code senyap.posts.*} (aturan M9). Kuota mengikuti functional spec §6 (tingkat "trust rendah" menyusul di
 * Fase 4 bersama trust score).
 *
 * @param counterFlushInterval jeda flush delta hati dari Redis ke DB (counter terbatch)
 * @param hotScoreInterval     jeda hitung ulang {@code hot_score} (spec: tiap 1 menit)
 * @param hotWindow            umur post yang ikut feed Populer (spec: 72 jam)
 */
@ConfigurationProperties("senyap.posts")
record PostsProperties(
        @DefaultValue("20") int postsPerDay,
        @DefaultValue("3") int postsPerDayNewAccount,
        @DefaultValue("200") int repliesPerDay,
        @DefaultValue("20") int repliesPerDayNewAccount,
        @DefaultValue("2s") Duration counterFlushInterval,
        @DefaultValue("1m") Duration hotScoreInterval,
        @DefaultValue("72h") Duration hotWindow) {
}
