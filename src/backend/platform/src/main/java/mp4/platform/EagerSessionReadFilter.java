package mp4.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Forces JDBC session lookup before Spring Security's deferred context resolution. */
final class EagerSessionReadFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/health");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        var session = request.getSession(false);
        if (session != null) session.getAttribute("SPRING_SECURITY_CONTEXT");
        chain.doFilter(request, response);
    }
}
