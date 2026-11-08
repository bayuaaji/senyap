package app.senyap.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Filter khusus test: setiap respons JSON dari aplikasi dipindai {@link AnonymityGuard}. Pelanggaran
 * dikumpulkan dan membuat test gagal di {@link IntegrationTest#penjagaAnonimitas()}.
 */
public class AnonymityGuardFilter extends OncePerRequestFilter {

    private final Supplier<List<Long>> userIds;
    private final List<String> violations = new CopyOnWriteArrayList<>();

    public AnonymityGuardFilter(Supplier<List<Long>> userIds) {
        this.userIds = userIds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
        } finally {
            String type = wrapped.getContentType();
            if (type != null && type.contains("json")) {
                String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
                Set<String> ids = new HashSet<>();
                userIds.get().forEach(id -> ids.add(Long.toString(id)));
                for (String v : AnonymityGuard.scan(body, ids)) {
                    violations.add(request.getMethod() + " " + request.getRequestURI() + ": " + v);
                }
            }
            wrapped.copyBodyToResponse();
        }
    }

    public List<String> violations() {
        return List.copyOf(violations);
    }

    public void clear() {
        violations.clear();
    }
}
