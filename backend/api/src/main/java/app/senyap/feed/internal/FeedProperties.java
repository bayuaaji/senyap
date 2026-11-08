package app.senyap.feed.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code senyap.feed.*} (aturan M9).
 *
 * @param firstPageTtl      cache daftar ID halaman pertama tiap feed (spec: 15 detik)
 * @param popularSize       jumlah post di sorted set Populer
 * @param popularWindow     umur post yang ikut Populer (spec: 72 jam)
 */
@ConfigurationProperties("senyap.feed")
record FeedProperties(@DefaultValue("15s") Duration firstPageTtl,
                      @DefaultValue("1000") int popularSize,
                      @DefaultValue("72h") Duration popularWindow) {
}
