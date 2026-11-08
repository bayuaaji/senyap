package app.senyap.admin.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Spring Security hanya untuk konsol moderator ({@code /admin/**}, sesi + CSRF). API publik {@code /v1/**}
 * tetap memakai {@code BearerTokenFilter} sendiri: chain kedua mengizinkan semua, tanpa sesi dan CSRF.
 *
 * <p>Login: SSO OIDC bila {@code spring.security.oauth2.client.registration.*} diisi (peran dari klaim
 * {@code senyap.admin.roles-claim}); login dev (pilih nama + peran) bila {@code senyap.admin.dev-login=true},
 * yang hanya diizinkan di profil local/test.
 */
@Configuration(proxyBeanMethods = false)
class AdminSecurityConfig {

    static final String ROLE_MODERATOR = "MODERATOR";
    static final String ROLE_LEGAL = "LEGAL";

    @Bean
    SecurityContextRepository adminSecurityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    @Order(1)
    SecurityFilterChain adminChain(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> registrations,
                                   SecurityContextRepository contexts, AdminProperties props, Environment env)
            throws Exception {
        boolean dev = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("local") || p.equals("test"));
        if (props.devLogin() && !dev) {
            throw new IllegalStateException("senyap.admin.dev-login hanya boleh di profil local/test; pakai SSO (OIDC)");
        }
        http.securityMatcher("/admin/**", "/oauth2/**", "/login/oauth2/**")
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/admin/login", "/admin/dev-login").permitAll()
                        .requestMatchers("/admin/legal/**").hasRole(ROLE_LEGAL)
                        .anyRequest().hasAnyRole(ROLE_MODERATOR, ROLE_LEGAL))
                .securityContext(c -> c.securityContextRepository(contexts))
                .exceptionHandling(e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/admin/login")))
                .logout(l -> l.logoutUrl("/admin/logout").logoutSuccessUrl("/admin/login?keluar"))
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(
                        "default-src 'self'; img-src 'self' data: http: https:; style-src 'self' 'unsafe-inline'")));
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(o -> o.loginPage("/admin/login").defaultSuccessUrl("/admin", true)
                    .userInfoEndpoint(u -> u.oidcUserService(oidcUserService(props))));
        }
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .logout(l -> l.disable())
                .build();
    }

    /** Mencegah Boot membuat user bawaan dengan password acak (tidak ada login password di Senyap). */
    @Bean
    UserDetailsService noPasswordUsers() {
        return new InMemoryUserDetailsManager();
    }

    /** Peran dari klaim grup OIDC → {@code ROLE_MODERATOR}/{@code ROLE_LEGAL}. Tanpa grup yang cocok = tanpa akses. */
    static OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(AdminProperties props) {
        var delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            Set<GrantedAuthority> authorities = new HashSet<>(user.getAuthorities());
            Object claim = user.getClaims().get(props.rolesClaim());
            List<String> groups = new ArrayList<>();
            if (claim instanceof Collection<?> c) {
                c.forEach(v -> groups.add(String.valueOf(v)));
            } else if (claim instanceof String s) {
                groups.addAll(Arrays.asList(s.split("[,\\s]+")));
            }
            if (groups.contains(props.moderatorGroup())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + ROLE_MODERATOR));
            }
            if (groups.contains(props.legalGroup())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + ROLE_LEGAL));
            }
            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo());
        };
    }
}
