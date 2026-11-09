package app.senyap.shared.security;

import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.error.ErrorResponse;
import app.senyap.shared.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Semua {@code /v1/**} wajib membawa {@code Authorization: Bearer <access token>}, kecuali endpoint
 * publik login. Token diverifikasi lewat JWKS, lalu {@code public_id} diterjemahkan ke user ID oleh
 * {@link PrincipalResolver}. Hasilnya ({@link AuthenticatedUser}) disimpan di atribut request.
 * Token tidak pernah ditulis ke log.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = AuthenticatedUser.class.getName();

    static final Set<String> PUBLIC_PATHS = Set.of("/v1/auth/otp", "/v1/auth/verify", "/v1/auth/refresh",
            "/v1/auth/email/register", "/v1/auth/email/login", "/v1/auth/email/forgot", "/v1/auth/email/reset");

    private final AccessTokenVerifier verifier;
    private final PrincipalResolver principals;
    private final JsonMapper json;

    public BearerTokenFilter(AccessTokenVerifier verifier, PrincipalResolver principals, JsonMapper json) {
        this.verifier = verifier;
        this.principals = principals;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/v1/") || PUBLIC_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthenticated(response);
            return;
        }
        var token = verifier.verify(header.substring(7).trim());
        if (token.isEmpty()) {
            unauthenticated(response);
            return;
        }
        var userId = principals.resolveActiveUserId(token.get().publicId());
        if (userId.isEmpty()) {
            unauthenticated(response);
            return;
        }
        request.setAttribute(ATTRIBUTE,
                new AuthenticatedUser(userId.getAsLong(), token.get().publicId(), token.get().deviceId()));
        chain.doFilter(request, response);
    }

    private void unauthenticated(HttpServletResponse response) throws IOException {
        var code = ErrorCode.UNAUTHENTICATED;
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        json.writeValue(response.getOutputStream(),
                ErrorResponse.of(code.name(), code.defaultMessage(), RequestIdFilter.current()));
    }
}
