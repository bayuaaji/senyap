package app.senyap.rt.ws;

import app.senyap.contracts.auth.AccessTokens;
import app.senyap.rt.auth.AccessTokenVerifier;
import app.senyap.rt.auth.HandshakeAuthFilter;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.socket.server.WebSocketService;

@Configuration(proxyBeanMethods = false)
class WebSocketConfig implements WebFluxConfigurer {

    static final String PATH = "/v1/ws";

    @Bean
    HandlerMapping webSocketMapping(GatewayWebSocketHandler handler) {
        return new SimpleUrlHandlerMapping(Map.of(PATH, handler), -1);
    }

    /** Principal hasil {@link HandshakeAuthFilter} ikut ke atribut sesi WebSocket. */
    @Override
    public WebSocketService getWebSocketService() {
        var service = new HandshakeWebSocketService();
        service.setSessionAttributePredicate(HandshakeAuthFilter.PRINCIPAL::equals);
        return service;
    }

    /** JWKS publik api ({@code senyap.rt.jwks-uri}), di-cache dan di-refresh oleh Nimbus (aturan M12). */
    @Bean
    JWKSource<SecurityContext> jwkSource(
            @Value("${senyap.rt.jwks-uri:http://localhost:8180" + AccessTokens.JWKS_PATH + "}") String jwksUri) {
        try {
            return JWKSourceBuilder.<SecurityContext>create(URI.create(jwksUri).toURL()).build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("senyap.rt.jwks-uri tidak valid", e);
        }
    }

    @Bean
    AccessTokenVerifier accessTokenVerifier(JWKSource<SecurityContext> jwkSource) {
        return new AccessTokenVerifier(jwkSource);
    }

    @Bean
    HandshakeAuthFilter handshakeAuthFilter(AccessTokenVerifier verifier) {
        return new HandshakeAuthFilter(PATH, verifier);
    }
}
