package app.senyap.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Browser mini untuk konsol moderator (HTML + sesi cookie + CSRF), untuk integration test. */
public class AdminClient {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    private final int port;
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private String csrf;

    public AdminClient(int port) {
        this.port = port;
    }

    /** Login dev: {@code roles} = MODERATOR dan/atau LEGAL. */
    public AdminClient login(String name, String... roles) {
        var page = get("/admin/login");
        var form = new LinkedHashMap<String, String>();
        form.put("name", name);
        var body = new StringBuilder(encode(form));
        for (String r : roles) {
            body.append("&roles=").append(r);
        }
        var res = postRaw("/admin/dev-login", body.toString(), token(page.body()));
        if (res.statusCode() != 302 || !res.headers().firstValue("Location").orElse("").endsWith("/admin")) {
            throw new IllegalStateException("login dev gagal: " + res.statusCode() + " " + res.headers().map());
        }
        return this;
    }

    public HttpResponse<String> get(String path) {
        var res = exchange(HttpRequest.newBuilder(uri(path)).GET().build());
        var m = CSRF.matcher(res.body());
        if (m.find()) {
            csrf = m.group(1);
        }
        return res;
    }

    /** POST form dengan token CSRF dari halaman terakhir yang dibuka. */
    public HttpResponse<String> post(String path, Map<String, String> form) {
        return postRaw(path, encode(form), csrf);
    }

    private HttpResponse<String> postRaw(String path, String body, String token) {
        String full = token == null ? body : body + (body.isEmpty() ? "" : "&") + "_csrf=" + enc(token);
        return exchange(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(full)).build());
    }

    private String token(String html) {
        var m = CSRF.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> exchange(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
