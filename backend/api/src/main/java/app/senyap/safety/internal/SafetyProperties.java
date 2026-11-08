package app.senyap.safety.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

/**
 * {@code senyap.safety.*} (aturan M9).
 *
 * @param blockedWordsFile   kata terlarang (FS-08)
 * @param classifierTermsFile istilah klasifikasi heuristik lokal
 * @param imageHashFile      daftar hash perseptual materi terlarang (kosong di repo)
 * @param imageHashMaxDistance jarak Hamming maksimum yang dianggap cocok (dHash 64-bit)
 * @param accountRefSecret   rahasia HMAC untuk {@code account_ref} per kasus; kosong = acak per proses
 * @param support            kartu bantuan risiko menyakiti diri
 */
@ConfigurationProperties("senyap.safety")
record SafetyProperties(
        @DefaultValue("classpath:safety/blocked-words.txt") Resource blockedWordsFile,
        @DefaultValue("classpath:safety/classifier-terms.txt") Resource classifierTermsFile,
        @DefaultValue("classpath:safety/known-image-hashes.txt") Resource imageHashFile,
        @DefaultValue("6") int imageHashMaxDistance,
        @DefaultValue("0.9") double nudityRejectSkinRatio,
        @DefaultValue("50") int reportsPerDay,
        @DefaultValue("20") int reportsPerDayNewAccount,
        @DefaultValue("") String accountRefSecret,
        @DefaultValue("30s") Duration consoleCacheTtl,
        @DefaultValue Support support) {

    /**
     * @param verified {@code false} = nomor/kontak belum dicek manusia. Di luar profil local/test aplikasi
     *                 menolak start bila masih {@code false} atau ada kontak placeholder.
     */
    record Support(
            @DefaultValue("Kamu tidak sendirian") String title,
            @DefaultValue("Sepertinya kamu sedang melewati masa yang berat. Bicara dengan seseorang bisa membantu.") String message,
            @DefaultValue("false") boolean verified,
            @DefaultValue List<Hotline> hotlines) {
    }

    record Hotline(String name, String contact, String hours) {
    }
}
