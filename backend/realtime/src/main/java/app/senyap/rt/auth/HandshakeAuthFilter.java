package app.senyap.rt.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Handshake {@code /v1/ws} wajib {@code Authorization: Bearer <access token>}. Token lewat query string
 * sengaja tidak didukung (bisa tercatat di log proxy). Tanpa token valid → 401 sebelum upgrade.
 * Principal dan token mentah disimpan di atribut exchange lalu disalin ke atribut sesi WebSocket: token
 * diteruskan gateway ke REST api atas nama user (gateway tidak punya akses DB). Token tidak pernah ditulis ke log.
 */
public class HandshakeAuthFilter implements WebFilter {

    public static final String PRINCIPAL = AccessTokenVerifier.Principal.class.getName();
    public static final String TOKEN = HandshakeAuthFilter.class.getName() + ".token";

    private final String path;
    private final AccessTokenVerifier verifier;

    public HandshakeAuthFilter(String path, AccessTokenVerifier verifier) {
        this.path = path;
        this.verifier = verifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().pathWithinApplication().value().equals(path)) {
            return chain.filter(exchange);
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return reject(exchange);
        }
        String token = header.substring(7).trim();
        return Mono.fromCallable(() -> verifier.verify(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(p -> {
                    if (p.isEmpty()) {
                        return reject(exchange);
                    }
                    exchange.getAttributes().put(PRINCIPAL, p.get());
                    exchange.getAttributes().put(TOKEN, token);
                    return chain.filter(exchange);
                });
    }

    private static Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return exchange.getResponse().setComplete();
    }
}
