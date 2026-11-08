package app.senyap.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code senyap.identity.*} (aturan M9).
 *
 * @param aliasChangeInterval batas ganti alias (functional spec §1.1: 1× per 7 hari)
 * @param suggestionTtl       umur {@code suggestion_token} dari {@code POST /v1/me/alias/suggest}
 * @param suggestPerMinute    batas "Acak lagi" per user per menit (anti-spam, bukan aturan produk)
 * @param purgeGrace          jeda antara {@code DELETE /v1/me} dan purge. Job jalan tiap {@code purgeInterval},
 *                            jadi purge selalu terjadi < {@code purgeGrace + purgeInterval} (spec: dalam 30 hari)
 * @param principalCacheTtl   cache public_id → user ID untuk autentikasi request
 */
@ConfigurationProperties("senyap.identity")
record IdentityProperties(
        @DefaultValue("7d") Duration aliasChangeInterval,
        @DefaultValue("10m") Duration suggestionTtl,
        @DefaultValue("60") int suggestPerMinute,
        @DefaultValue("29d") Duration purgeGrace,
        @DefaultValue("1h") Duration purgeInterval,
        @DefaultValue("30s") Duration principalCacheTtl) {
}
