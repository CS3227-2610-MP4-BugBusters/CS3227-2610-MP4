package mp4.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Converts JDBC session repository failures, including response-time saves, into safe JSON. */
final class SessionFailureBoundaryFilter extends OncePerRequestFilter implements Ordered {
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, buffered);
            buffered.copyBodyToResponse();
        } catch (RuntimeException failure) {
            if (isStorageFailure(failure)) {
                buffered.resetBuffer();
                if (!response.isCommitted()) AuthHttp.error(buffered, 503, "STORAGE_UNAVAILABLE",
                        "Authentication is temporarily unavailable");
            } else {
                buffered.resetBuffer();
                if (!response.isCommitted()) AuthHttp.error(buffered, 500, "INTERNAL_ERROR", "Request could not be completed");
            }
            buffered.copyBodyToResponse();
        }
    }

    private static boolean isStorageFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException || cause instanceof DataAccessException
                    || cause.getClass().getName().contains("SessionRepository")) return true;
        }
        return false;
    }
}
