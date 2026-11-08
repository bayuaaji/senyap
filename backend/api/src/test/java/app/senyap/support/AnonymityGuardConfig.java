package app.senyap.support;

import app.senyap.shared.db.ModuleDatabase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@TestConfiguration(proxyBeanMethods = false)
public class AnonymityGuardConfig {

    @Bean
    AnonymityGuardFilter anonymityGuardFilter(@Qualifier("identityDatabase") ModuleDatabase identity) {
        return new AnonymityGuardFilter(() -> identity.jdbc().sql("SELECT id FROM users").query(Long.class).list());
    }

    @Bean
    FilterRegistrationBean<AnonymityGuardFilter> anonymityGuardRegistration(AnonymityGuardFilter filter) {
        var reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
