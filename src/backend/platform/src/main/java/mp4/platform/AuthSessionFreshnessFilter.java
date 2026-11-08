package mp4.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves current account state before Spring Security applies path role authorization. */
final class AuthSessionFreshnessFilter extends OncePerRequestFilter {
    static final String CREATED = "MP4_AUTH_CREATED";
    static final String LAST = "MP4_AUTH_LAST";
    private static final Duration IDLE = Duration.ofMinutes(30);
    private static final Duration ABSOLUTE = Duration.ofHours(8);

    private final AuthAccountRepository accounts;
    private final Clock clock;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    AuthSessionFreshnessFilter(AuthAccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) return true;
        return path.equals("/api/health") || path.equals("/api/auth/csrf")
                || path.equals("/api/auth/login") || path.equals("/api/auth/logout");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthProvider.CurrentUser previous)) {
            deny(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        var session = request.getSession(false);
        if (session == null) {
            deny(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        long now = clock.millis();
        Long created = (Long) session.getAttribute(CREATED);
        Long last = (Long) session.getAttribute(LAST);
        if (created == null || last == null || now - created >= ABSOLUTE.toMillis()
                || now - last >= IDLE.toMillis()) {
            expire(session);
            deny(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }

        try {
            var account = accounts.findById(previous.id());
            if (account.isEmpty()) {
                expire(session);
                deny(response, 401, "UNAUTHENTICATED", "Authentication required");
                return;
            }
            var current = account.get();
            if (!current.active()) {
                expire(session);
                deny(response, 403, "FORBIDDEN", "Account is inactive");
                return;
            }
            if (current.generation() != previous.generation()) {
                expire(session);
                deny(response, 401, "UNAUTHENTICATED", "Authentication required");
                return;
            }

            AuthProvider.CurrentUser refreshed = new AuthProvider.CurrentUser(current.id(),
                    current.displayName(), current.role(), current.generation());
            Authentication freshAuthentication = new UsernamePasswordAuthenticationToken(refreshed, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + refreshed.role())));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(freshAuthentication);
            SecurityContextHolder.setContext(context);
            session.setAttribute(LAST, now);
            contexts.saveContext(context, request, response);
            chain.doFilter(request, response);
        } catch (java.sql.SQLException unavailable) {
            throw new org.springframework.dao.DataAccessResourceFailureException("Account state unavailable", unavailable);
        }
    }

    private static void expire(jakarta.servlet.http.HttpSession session) {
        SecurityContextHolder.clearContext();
        session.invalidate();
    }

    private static void deny(HttpServletResponse response, int status, String code, String message) throws IOException {
        AuthHttp.error(response, status, code, message);
    }
}
