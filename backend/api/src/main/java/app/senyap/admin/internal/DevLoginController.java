package app.senyap.admin.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Halaman login konsol: tombol SSO (bila OIDC dikonfigurasi) dan form login dev (local/test saja). */
@Controller
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class DevLoginController {

    private final AdminProperties props;
    private final SecurityContextRepository contexts;
    private final ObjectProvider<ClientRegistrationRepository> registrations;

    DevLoginController(AdminProperties props, SecurityContextRepository contexts,
                       ObjectProvider<ClientRegistrationRepository> registrations) {
        this.props = props;
        this.contexts = contexts;
        this.registrations = registrations;
    }

    @GetMapping("/admin/login")
    String login(Model model) {
        var sso = new ArrayList<String>();
        if (registrations.getIfAvailable() instanceof Iterable<?> repo) {
            for (Object r : repo) {
                sso.add(((ClientRegistration) r).getRegistrationId());
            }
        }
        model.addAttribute("sso", sso);
        model.addAttribute("devLogin", props.devLogin());
        return "admin/login";
    }

    @PostMapping("/admin/dev-login")
    String devLogin(@RequestParam String name, @RequestParam(required = false) List<String> roles,
                    HttpServletRequest request, HttpServletResponse response) {
        if (!props.devLogin()) {
            response.setStatus(404);
            return "admin/login";
        }
        String clean = name == null ? "" : name.strip().replaceAll("[^A-Za-z0-9._-]", "");
        if (clean.isEmpty() || clean.length() > 40) {
            return "redirect:/admin/login?gagal";
        }
        var authorities = new ArrayList<GrantedAuthority>();
        for (String r : roles == null ? List.<String>of() : roles) {
            if (r.equals(AdminSecurityConfig.ROLE_MODERATOR) || r.equals(AdminSecurityConfig.ROLE_LEGAL)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + r));
            }
        }
        if (authorities.isEmpty()) {
            return "redirect:/admin/login?gagal";
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated("dev:" + clean, null, authorities);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        contexts.saveContext(context, request, response);
        return "redirect:/admin";
    }
}
