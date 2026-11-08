package app.senyap.shared.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IdConfig {

    @Bean
    Tsid tsid(@Value("${senyap.id.node:-1}") long node) {
        return node < 0 ? Tsid.randomNode() : new Tsid(node);
    }
}
