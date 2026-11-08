package app.senyap.support;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Gateway realtime sebagai proses terpisah (jar {@code backend/realtime/target}), persis seperti di produksi:
 * hanya bicara ke api lewat HTTP (JWKS + REST) dan ke Redis. Dipakai test e2e untuk restart gateway.
 */
public final class GatewayProcess implements AutoCloseable {

    private final Path jar;
    private final int port;
    private final List<String> args;
    private final Path log;
    private Process process;

    public GatewayProcess(Path jar, int apiPort, String redisHost, int redisPort) {
        this.jar = jar;
        this.port = freePort();
        this.log = jar.getParent().resolve("e2e-gateway.log");
        this.args = List.of(
                "--server.port=" + port,
                "--senyap.rt.api-base-url=http://localhost:" + apiPort,
                "--senyap.rt.jwks-uri=http://localhost:" + apiPort + "/.well-known/jwks.json",
                "--spring.data.redis.host=" + redisHost,
                "--spring.data.redis.port=" + redisPort);
    }

    /** Jar gateway hasil build, atau {@code null} bila belum di-build (test dilewati). */
    public static Path findJar() {
        File dir = new File("../realtime/target");
        File[] jars = dir.listFiles((d, n) -> n.startsWith("realtime-") && n.endsWith(".jar") && !n.contains("plain"));
        return jars == null || jars.length == 0 ? null : jars[0].toPath().toAbsolutePath();
    }

    public int port() {
        return port;
    }

    public void start() {
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var cmd = new java.util.ArrayList<String>(List.of(java, "-jar", jar.toString()));
            cmd.addAll(args);
            process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        var http = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // belum siap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (!process.isAlive()) {
                throw new IllegalStateException("gateway berhenti saat start, lihat " + log);
            }
            sleep(200);
        }
        throw new IllegalStateException("gateway tidak siap dalam 60 dtk, lihat " + log);
    }

    /** Hentikan proses (SIGTERM = graceful shutdown). */
    public void stop() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process = null;
    }

    @Override
    public void close() {
        stop();
    }

    public Path log() {
        return log;
    }

    private static int freePort() {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
