package app.senyap.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Klien WebSocket uji (JDK) untuk gateway realtime: menampung frame masuk beserta waktu terimanya. */
public final class WsClient implements AutoCloseable {

    public record Frame(JsonNode json, long receivedAtNanos) {

        public String t() {
            return json.get("t").asString();
        }

        public JsonNode d() {
            return json.get("d");
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final WebSocket ws;

    private WsClient(URI uri, String token) {
        this.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            frames.add(new Frame(JSON.readTree(buf.toString()), System.nanoTime()));
                            buf.setLength(0);
                        }
                        w.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket w, int status, String reason) {
                        closed.complete(status);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket w, Throwable error) {
                        closed.complete(-1);
                    }
                }).join();
    }

    public static WsClient connect(int port, String token) {
        return new WsClient(URI.create("ws://localhost:" + port + "/v1/ws"), token);
    }

    public void send(String type, String id, String dataJson) {
        ws.sendText("{\"t\":\"" + type + "\",\"id\":\"" + id + "\",\"d\":" + dataJson + "}", true).join();
    }

    /** Frame berikutnya yang cocok; frame lain yang dilewati dibuang. */
    public Frame await(Predicate<Frame> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("frame yang ditunggu tidak datang dalam " + timeout);
            }
            try {
                Frame f = frames.poll(left, TimeUnit.NANOSECONDS);
                if (f != null && match.test(f)) {
                    return f;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Semua frame yang sudah masuk (dan mengosongkan antrean). */
    public List<Frame> drain() {
        var out = new ArrayList<Frame>();
        frames.drainTo(out);
        return out;
    }

    public CompletableFuture<Integer> closed() {
        return closed;
    }

    @Override
    public void close() {
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "selesai").get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            ws.abort();
        }
    }
}
