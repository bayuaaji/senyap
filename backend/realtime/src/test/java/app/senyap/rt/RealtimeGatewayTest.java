package app.senyap.rt;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeGatewayTest {

    @LocalServerPort
    int port;

    @Test
    void healthUp() {
        String body = WebClient.create("http://localhost:" + port).get().uri("/actuator/health")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void pingDibalasPongDanFrameLainError() {
        List<String> received = new ArrayList<>();
        new ReactorNettyWebSocketClient().execute(URI.create("ws://localhost:" + port + "/v1/ws"), session ->
                        session.send(reactor.core.publisher.Flux.just(
                                        session.textMessage("{\"t\":\"ping\",\"id\":\"f1\",\"d\":{}}"),
                                        session.textMessage("{\"t\":\"msg.send\",\"id\":\"f2\",\"d\":{}}"),
                                        session.textMessage("bukan json")))
                                .thenMany(session.receive().map(WebSocketMessage::getPayloadAsText).take(3)
                                        .doOnNext(received::add))
                                .then())
                .block(Duration.ofSeconds(10));

        assertThat(received).hasSize(3);
        assertThat(received.get(0)).contains("\"t\":\"pong\"").contains("\"id\":\"f1\"");
        assertThat(received.get(1)).contains("\"t\":\"error\"").contains("\"ref_id\":\"f2\"").contains("NOT_IMPLEMENTED");
        assertThat(received.get(2)).contains("\"t\":\"error\"").contains("VALIDATION_FAILED");
    }
}
