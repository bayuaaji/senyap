package app.senyap.admin.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code senyap.admin.*}: akses konsol moderator.
 *
 * @param devLogin   login tanpa SSO (pilih nama + peran) untuk laptop/test. Hanya boleh {@code true} di profil
 *                   local/test; selain itu aplikasi gagal start.
 * @param rolesClaim klaim token OIDC yang berisi grup/peran (mis. {@code groups}, {@code roles})
 * @param moderatorGroup nilai klaim untuk peran moderator
 * @param legalGroup     nilai klaim untuk peran legal (penelusuran identitas atas permintaan hukum)
 */
@ConfigurationProperties("senyap.admin")
record AdminProperties(
        @DefaultValue("false") boolean devLogin,
        @DefaultValue("groups") String rolesClaim,
        @DefaultValue("senyap-moderator") String moderatorGroup,
        @DefaultValue("senyap-legal") String legalGroup) {
}
