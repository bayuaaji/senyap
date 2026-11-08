package app.senyap.rt.ws;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

@Configuration(proxyBeanMethods = false)
class WebSocketConfig {

    static final String PATH = "/v1/ws";

    @Bean
    HandlerMapping webSocketMapping(GatewayWebSocketHandler handler) {
        return new SimpleUrlHandlerMapping(Map.of(PATH, handler), -1);
    }
}
