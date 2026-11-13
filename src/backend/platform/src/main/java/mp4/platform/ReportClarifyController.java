package mp4.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/reporter/report-clarify. The security chain enforces the Reporter role and CSRF; this
 * controller also refuses non-Reporter, inactive or stale sessions before the provider is called and
 * again before a result is delivered (RC-02), as the Developer suggestion endpoint does.
 */
@RestController
final class ReportClarifyController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportClarifyController.class);
    private static final String SECURITY_CONTEXT = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    private static final String CREATED = AuthSessionFreshnessFilter.CREATED;
    private static final String LAST = AuthSessionFreshnessFilter.LAST;
    private static final Duration ABSOLUTE_SESSION_LIMIT = Duration.ofHours(8);
    private static final Duration IDLE_SESSION_LIMIT = Duration.ofMinutes(30);
    // Same request-body ceiling as report creation (ReporterReportsHttpHandler).
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final String REPORTER = "REPORTER";

    private final ReportClarifyService service;
    private final AuthAccountRepository accounts;
    private final Clock clock;
    private final SessionRepository<? extends Session> sessions;

    @SuppressWarnings({"unchecked", "rawtypes"})
    ReportClarifyController(ReportClarifyService service, AuthAccountRepository accounts, Clock clock,
            ObjectProvider<SessionRepository> sessionRepositories) {
        this.service = service;
        this.accounts = accounts;
        this.clock = clock;
        this.sessions = (SessionRepository<? extends Session>) sessionRepositories.getIfAvailable();
    }

    @PostMapping("/api/reporter/report-clarify")
    void clarify(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        AuthProvider.CurrentUser actor = currentReporter();
        HttpSession httpSession = request.getSession(false);
        if (actor == null || httpSession == null || sessions == null) {
            AuthHttp.error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        String sessionId = httpSession.getId();
        Long createdAt = (Long) httpSession.getAttribute(CREATED);
        Long lastAt = (Long) httpSession.getAttribute(LAST);
        if (!validSessionTimes(createdAt, lastAt, clock.millis())) {
            AuthHttp.error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        int refusal = activeReporterRefusal(actor);
        if (refusal != 0) {
            refuse(response, refusal);
            return;
        }
        if (!freshSession(sessionId, actor, createdAt, clock.millis())) {
            AuthHttp.error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }

        byte[] body = readBounded(request);
        if (request.getQueryString() != null || !isJson(request.getContentType()) || body == null) {
            AuthHttp.error(response, 400, "VALIDATION_FAILED", "The report check request is invalid");
            return;
        }

        ReportClarifyService.Reply reply = service.handle(actor.id(), body);

        // The session or account may have changed while the model was running; discard the result then.
        if (reply.status() == 200) {
            refusal = activeReporterRefusal(actor);
            if (refusal == 0 && !freshSession(sessionId, actor, createdAt, clock.millis())) refusal = 401;
            if (refusal != 0) {
                LOGGER.info("event=reporter_report_check_discarded status={}", refusal);
                refuse(response, refusal);
                return;
            }
        }
        response.setStatus(reply.status());
        if (reply.retryAfterSeconds() != null) {
            response.setHeader("Retry-After", Integer.toString(reply.retryAfterSeconds()));
        }
        response.setContentType("application/json; charset=utf-8");
        response.getOutputStream().write(reply.body().getBytes(StandardCharsets.UTF_8));
    }

    /** 0 when the stored account is an active Reporter of the same generation; otherwise 401, 403 or 503. */
    private int activeReporterRefusal(AuthProvider.CurrentUser actor) {
        try {
            var account = accounts.findById(actor.id());
            if (account.isEmpty() || account.get().generation() != actor.generation()) return 401;
            if (!account.get().active() || !REPORTER.equals(account.get().role())) return 403;
            return 0;
        } catch (java.sql.SQLException | RuntimeException unavailable) {
            return 503;
        }
    }

    private static void refuse(HttpServletResponse response, int status) throws IOException {
        switch (status) {
            case 403 -> AuthHttp.error(response, 403, "FORBIDDEN", "Access denied");
            case 503 -> AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE",
                    "Report checks are temporarily unavailable");
            default -> AuthHttp.error(response, 401, "UNAUTHENTICATED", "Authentication required");
        }
    }

    private boolean freshSession(String sessionId, AuthProvider.CurrentUser actor, Long initialCreated, long now) {
        try {
            Session stored = sessions.findById(sessionId);
            if (stored == null || stored.isExpired()) return false;
            Long created = stored.getAttribute(CREATED);
            Long last = stored.getAttribute(LAST);
            if (created == null || !created.equals(initialCreated) || !validSessionTimes(created, last, now)) {
                return false;
            }
            SecurityContext context = stored.getAttribute(SECURITY_CONTEXT);
            Authentication authentication = context == null ? null : context.getAuthentication();
            return authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof AuthProvider.CurrentUser current
                    && current.id().equals(actor.id()) && current.generation() == actor.generation()
                    && REPORTER.equals(current.role());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean validSessionTimes(Long created, Long last, long now) {
        return created != null && last != null && created <= now && last <= now
                && now - created < ABSOLUTE_SESSION_LIMIT.toMillis()
                && now - last < IDLE_SESSION_LIMIT.toMillis();
    }

    private static AuthProvider.CurrentUser currentReporter() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthProvider.CurrentUser actor)
                || !REPORTER.equals(actor.role())) return null;
        return actor;
    }

    /** The body, or null when it exceeds the report-creation ceiling. */
    private static byte[] readBounded(HttpServletRequest request) throws IOException {
        try (InputStream input = request.getInputStream()) {
            byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        }
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return "application".equalsIgnoreCase(parsed.getType())
                    && "json".equalsIgnoreCase(parsed.getSubtype());
        } catch (InvalidMediaTypeException invalid) {
            return false;
        }
    }
}
