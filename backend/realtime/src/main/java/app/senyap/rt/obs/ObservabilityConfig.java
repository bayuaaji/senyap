package app.senyap.rt.obs;

import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    @Bean
    Traces traces(ObjectProvider<OpenTelemetry> otel) {
        return new Traces(otel);
    }
}
