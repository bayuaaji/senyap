package app.senyap.shared.security;

import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.net.MalformedURLException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Autentikasi HTTP (aturan M12). Sumber JWKS:
 * <ul>
 *   <li>{@code senyap.security.jwks-uri} di-set → JWKS diambil lewat HTTP (dipakai service hasil pecahan);</li>
 *   <li>tidak di-set → JWKS dari penerbit di proses yang sama ({@link LocalJwks}, modul auth).</li>
 * </ul>
 * Controller menerima {@link AuthenticatedUser} sebagai parameter method.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig implements WebMvcConfigurer {

    @Bean
    AccessTokenVerifier accessTokenVerifier(@Value("${senyap.security.jwks-uri:}") String jwksUri,
                                            ObjectProvider<LocalJwks> local) {
        JWKSource<SecurityContext> source;
        if (!jwksUri.isBlank()) {
            try {
                source = JWKSourceBuilder.create(URI.create(jwksUri).toURL()).build();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("senyap.security.jwks-uri tidak valid", e);
            }
        } else {
            source = (selector, ctx) -> selector.select(local.getObject().publicJwks());
        }
        return new AccessTokenVerifier(source);
    }

    @Bean
    FilterRegistrationBean<BearerTokenFilter> bearerTokenFilter(AccessTokenVerifier verifier,
                                                                PrincipalResolver principals, JsonMapper json) {
        var reg = new FilterRegistrationBean<>(new BearerTokenFilter(verifier, principals, json));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.addUrlPatterns("/v1/*");
        return reg;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == AuthenticatedUser.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                          NativeWebRequest request, WebDataBinderFactory binder) {
                Object user = request.getAttribute(BearerTokenFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                if (user == null) {
                    throw new ApiException(ErrorCode.UNAUTHENTICATED);
                }
                return user;
            }
        });
    }
}
