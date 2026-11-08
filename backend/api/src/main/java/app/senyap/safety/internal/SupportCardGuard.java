package app.senyap.safety.internal;

import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Kartu bantuan risiko menyakiti diri (FS-08) tidak boleh terbit dengan nomor karangan. Nomor hotline
 * Indonesia <b>belum diverifikasi</b> (pertanyaan terbuka di PROGRESS.md): di luar profil local/test,
 * aplikasi gagal start bila {@code senyap.safety.support.verified=false}, daftar hotline kosong, atau masih ada
 * kontak placeholder.
 */
@Component
class SupportCardGuard implements InitializingBean {

    static final String PLACEHOLDER = "BELUM-DIVERIFIKASI";

    private final SafetyProperties props;
    private final Environment env;

    SupportCardGuard(SafetyProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        boolean dev = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("local") || p.equals("test"));
        if (dev) {
            return;
        }
        var s = props.support();
        boolean placeholder = s.hotlines().stream()
                .anyMatch(h -> h.contact() == null || h.contact().isBlank() || h.contact().contains(PLACEHOLDER));
        if (!s.verified() || s.hotlines().isEmpty() || placeholder) {
            throw new IllegalStateException("senyap.safety.support belum diverifikasi: isi hotline Indonesia yang "
                    + "sudah dicek manusia lalu set senyap.safety.support.verified=true (lihat PROGRESS.md)");
        }
    }
}
