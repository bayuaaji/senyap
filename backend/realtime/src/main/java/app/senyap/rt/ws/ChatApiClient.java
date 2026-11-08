package app.senyap.rt.ws;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gateway → REST api modul chat (build guide §1.2 langkah 2), atas nama user dengan access token sesinya.
 * Dengan begitu validasi, rate limit, dan penulisan ke DB tetap satu jalur dengan fallback REST client.
 */
@Component
class ChatApiClient {

    /** Hasil panggilan: status HTTP + body JSON (bisa {@code null}). */
    record Result(int status, JsonNode body) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        String errorCode() {
            return body != null && body.has("error") ? body.get("error").get("code").asString() : "INTERNAL";
        }

        String errorMessage() {
            return body != null && body.has("error") ? body.get("error").get("message").asString()
                    : "Terjadi kesalahan. Coba lagi nanti.";
        }
    }

    private final WebClient web;
    private final JsonMapper json;
    private final Duration timeout;

    ChatApiClient(JsonMapper json,
                  @Value("${senyap.rt.api-base-url:http://localhost:8180}") String baseUrl,
                  @Value("${senyap.rt.api-timeout:5s}") Duration timeout) {
        this.web = WebClient.builder().baseUrl(baseUrl).build();
        this.json = json;
        this.timeout = timeout;
    }

    Mono<Result> post(String token, String path, Map<String, Object> body) {
        return web.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json.writeValueAsString(body))
                .exchangeToMono(this::toResult)
                .timeout(timeout)
                .onErrorResume(e -> Mono.just(new Result(503, null)));
    }

    Mono<Result> get(String token, String path) {
        return web.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchangeToMono(this::toResult)
                .timeout(timeout)
                .onErrorResume(e -> Mono.just(new Result(503, null)));
    }

    private Mono<Result> toResult(org.springframework.web.reactive.function.client.ClientResponse r) {
        int status = r.statusCode().value();
        return r.bodyToMono(String.class)
                .map(s -> new Result(status, s.isBlank() ? null : json.readTree(s)))
                .defaultIfEmpty(new Result(status, null));
    }
}
