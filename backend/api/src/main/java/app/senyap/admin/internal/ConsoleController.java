package app.senyap.admin.internal;

import app.senyap.safety.ModerationConsoleApi;
import app.senyap.safety.ModeratorAction;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Konsol moderator (FS-09), server-rendered (Thymeleaf) di modul admin: antrean per prioritas, detail kasus
 * dengan snapshot, aksi, audit log, dan penelusuran identitas khusus peran legal. Bentuk paling sederhana:
 * satu proses dengan API, tanpa SPA/build frontend terpisah; data lewat {@link ModerationConsoleApi} (safety).
 * Halaman HTML ini bukan API client app, jadi tidak lewat penjaga anonimitas JSON; nomor HP tetap tidak pernah
 * tampil (hanya {@code phone_hmac} untuk peran legal).
 */
@Controller
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class ConsoleController {

    static final int PAGE = 50;

    private final ModerationConsoleApi console;

    ConsoleController(ModerationConsoleApi console) {
        this.console = console;
    }

    @GetMapping("/admin")
    String queue(@RequestParam(required = false) Integer priority, @RequestParam(required = false) String status,
                 @RequestParam(required = false) Long after, Model model, Authentication auth) {
        var items = console.queue(priority, status, after, PAGE + 1);
        boolean more = items.size() > PAGE;
        var page = more ? items.subList(0, PAGE) : items;
        model.addAttribute("items", page);
        model.addAttribute("next", more ? page.getLast().reportId() : null);
        model.addAttribute("priority", priority);
        model.addAttribute("status", status == null ? "OPEN" : status);
        model.addAttribute("me", actor(auth));
        model.addAttribute("legal", isLegal(auth));
        return "admin/queue";
    }

    @GetMapping("/admin/cases/{id}")
    String detail(@PathVariable long id, Model model, Authentication auth) {
        var c = console.caseDetail(id, actor(auth));
        if (c.isEmpty()) {
            return "redirect:/admin?tidakada";
        }
        model.addAttribute("c", c.get());
        model.addAttribute("actions", ModeratorAction.values());
        model.addAttribute("me", actor(auth));
        model.addAttribute("legal", isLegal(auth));
        return "admin/case";
    }

    @PostMapping("/admin/cases/{id}/actions")
    String act(@PathVariable long id, @RequestParam ModeratorAction action, @RequestParam String reason,
               Authentication auth, RedirectAttributes flash) {
        try {
            console.act(id, action, reason, actor(auth));
            flash.addFlashAttribute("ok", "Aksi " + action + " tercatat.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/cases/" + id;
    }

    @GetMapping("/admin/audit")
    String audit(@RequestParam(required = false) Long before, Model model, Authentication auth) {
        var items = console.audit(before, PAGE + 1);
        boolean more = items.size() > PAGE;
        var page = more ? items.subList(0, PAGE) : items;
        model.addAttribute("items", page);
        model.addAttribute("next", more ? page.getLast().id() : null);
        model.addAttribute("me", actor(auth));
        model.addAttribute("legal", isLegal(auth));
        return "admin/audit";
    }

    @GetMapping("/admin/legal/cases/{id}/identity")
    String identityForm(@PathVariable long id, Model model, Authentication auth) {
        model.addAttribute("reportId", id);
        model.addAttribute("me", actor(auth));
        model.addAttribute("legal", true);
        return "admin/identity";
    }

    @PostMapping("/admin/legal/cases/{id}/identity")
    String identity(@PathVariable long id, @RequestParam String reason, Model model, Authentication auth) {
        model.addAttribute("reportId", id);
        model.addAttribute("me", actor(auth));
        model.addAttribute("legal", true);
        try {
            var d = console.lookupIdentity(id, reason, actor(auth));
            model.addAttribute("d", d.orElse(null));
            model.addAttribute("searched", true);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "admin/identity";
    }

    /** Identitas moderator untuk audit: {@code dev:<nama>} atau {@code sso:<email|sub>}. */
    static String actor(Authentication auth) {
        if (auth.getPrincipal() instanceof OidcUser oidc) {
            return "sso:" + (oidc.getEmail() != null ? oidc.getEmail() : oidc.getSubject());
        }
        return auth.getName();
    }

    static boolean isLegal(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> List.of("ROLE_LEGAL").contains(a.getAuthority()));
    }
}
