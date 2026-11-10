package mp4.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import mp4.developer.AssignedReportListHttpHandler;
import mp4.developer.AssignedReportStore;
import mp4.developer.ChecklistDraftHttpHandler;
import mp4.developer.ChecklistDraftStore;
import mp4.developer.ChecklistShareHttpHandler;
import mp4.developer.ChecklistSharedHttpHandler;
import mp4.developer.JdbcChecklistShareService;
import mp4.reporter.ReporterReportsHttpHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;

@RestController
final class AuthController {
    private static final String CREATED = AuthSessionFreshnessFilter.CREATED;
    private static final String LAST = AuthSessionFreshnessFilter.LAST;

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final Clock clock;
    private final LoginAttemptStore attempts;
    private final AssignedReportListHttpHandler assignedReports;
    private final ChecklistDraftHttpHandler checklistDraft;
    private final ChecklistShareHttpHandler checklistShare;
    private final ChecklistSharedHttpHandler sharedChecklist;
    private final ReporterReportsHttpHandler reporterReports;
    private final String publicOrigin;

    AuthController(AuthenticationManager authenticationManager, SessionAuthenticationStrategy sessionStrategy,
                   HttpSessionCsrfTokenRepository csrfRepository, Clock clock, LoginAttemptStore attempts,
                   DataSource dataSource, @Value("${mp4.auth.public-origin:}") String publicOrigin) {
        this.authenticationManager = authenticationManager;
        this.sessionStrategy = sessionStrategy;
        this.clock = clock;
        this.attempts = attempts;
        var actorDataSource = new ActorTransactionDataSource(dataSource);
        this.assignedReports = new AssignedReportListHttpHandler(new AssignedReportStore(actorDataSource));
        this.checklistDraft = new ChecklistDraftHttpHandler(new ChecklistDraftStore(actorDataSource));
        this.reporterReports = new ReporterReportsHttpHandler(actorDataSource);
        this.checklistShare = new ChecklistShareHttpHandler(new JdbcChecklistShareService(actorDataSource,
                (connection, request, version, invalidated) ->
                        mp4.maintainer.storage.SharedReportHistory.checklistPublished(connection, request.reportId(),
                                request.actor().id(), version, invalidated)));
        var maintainerReports = new mp4.maintainer.storage.JdbcMaintainerRepository(dataSource);
        this.sharedChecklist = new ChecklistSharedHttpHandler(actorDataSource, (actor, reportId) -> {
            try { return maintainerReports.canViewSharedChecklist(MaintainerController.actor(), reportId); }
            catch (mp4.maintainer.workflow.MaintainerService.ServiceException denied) {
                if (denied.statusCode() == 401 || denied.statusCode() == 403) return false;
                throw new java.sql.SQLException("Maintainer access check unavailable");
            }
        });
        this.publicOrigin = publicOrigin;
    }

    @GetMapping("/api/auth/csrf") Object csrf(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        if (!emptyRequestShape(request)) return badRequest(response);
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) return Map.of("token", "", "headerName", "X-CSRF-TOKEN");
        request.getSession(true);
        return Map.of("token", token.getToken(), "headerName", "X-CSRF-TOKEN");
    }

    @PostMapping(value = "/api/auth/login", consumes = "application/x-www-form-urlencoded")
    Object login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        if (request.getQueryString() != null || !originAllowed(request)) {
            AuthHttp.error(response, request.getQueryString() == null ? 403 : 400,
                    request.getQueryString() == null ? "CSRF_INVALID" : "VALIDATION_FAILED", "Request denied");
            return null;
        }

        LoginFormParser.LoginForm form;
        try {
            form = LoginFormParser.parse(request);
        } catch (IllegalArgumentException invalid) {
            AuthHttp.error(response, 400, "VALIDATION_FAILED", "Invalid authentication request");
            return null;
        }
        final int retryAfter;
        try {
            retryAfter = attempts.admit(form.username(), request.getRemoteAddr());
        } catch (Exception unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Authentication is temporarily unavailable");
            return null;
        }
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Integer.toString(retryAfter));
            AuthHttp.error(response, 429, "AUTH_RATE_LIMITED", "Too many login attempts");
            return null;
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(form.username(), form.password()));
        } catch (BadCredentialsException invalid) {
            AuthHttp.error(response, 401, "UNAUTHENTICATED", "Credentials are invalid");
            return null;
        } catch (AuthenticationServiceException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Authentication is temporarily unavailable");
            return null;
        }

        try {
            HttpSession session = request.getSession(true);
            sessionStrategy.onAuthentication(authentication, request, response);
            session.setAttribute(CREATED, clock.millis());
            session.setAttribute(LAST, clock.millis());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);
            return userJson((AuthProvider.CurrentUser) authentication.getPrincipal());
        } catch (RuntimeException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Authentication is temporarily unavailable");
            return null;
        }
    }

    @GetMapping("/api/auth/me") Object currentUser(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthProvider.CurrentUser user)) {
            AuthHttp.error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return null;
        }
        if (!emptyRequestShape(request)) return badRequest(response);
        return userJson(user);
    }

    @PostMapping("/api/auth/logout") void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        if (!emptyRequestShape(request)) { badRequest(response); return; }
        if (!originAllowed(request)) { AuthHttp.error(response, 403, "CSRF_INVALID", "Request denied"); return; }
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        contextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        response.setStatus(204);
    }

    @RequestMapping("/api/developer/reports") void assignedReports(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AuthProvider.CurrentUser user = (AuthProvider.CurrentUser)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var actor = new ChecklistDraftStore.Actor(user.id(), ChecklistDraftStore.Role.DEVELOPER, true);
        roleRequest(request, response, output -> assignedReports.handle(new ServletHttpExchange(request, output), actor));
    }

    // The handler parses the exact request path; identity comes only from the session principal.
    @RequestMapping({"/api/reporter/reports", "/api/reporter/reports/**"})
    void reporterReports(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthProvider.CurrentUser user = (AuthProvider.CurrentUser)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        // AuthSessionFreshnessFilter has already refused inactive accounts; transactions recheck it.
        roleRequest(request, response, output ->
                reporterReports.handle(new ServletHttpExchange(request, output), user.id(), user.role(), true));
    }

    @RequestMapping("/api/reports/{reportId}/checklist/share")
    void shareChecklist(HttpServletRequest request, HttpServletResponse response) throws IOException {
        roleRequest(request, response, output -> checklistShare.handle(new ServletHttpExchange(request, output), checklistActor()));
    }

    @RequestMapping("/api/reports/{reportId}/checklist/draft")
    void saveChecklistDraft(HttpServletRequest request, HttpServletResponse response) throws IOException {
        roleRequest(request, response, output -> checklistDraft.handle(new ServletHttpExchange(request, output), checklistActor()));
    }

    @RequestMapping("/api/reports/{reportId}/checklist/shared")
    void readSharedChecklist(HttpServletRequest request, HttpServletResponse response) throws IOException {
        roleRequest(request, response, output -> sharedChecklist.handle(new ServletHttpExchange(request, output), checklistActor()));
    }

    private static void roleRequest(HttpServletRequest request, HttpServletResponse response, RoleRequest action) throws IOException {
        var buffered = new org.springframework.web.util.ContentCachingResponseWrapper(response);
        ActorTransactionDataSource.clearRevocation();
        try {
            try { action.run(buffered); }
            catch (ActorTransactionDataSource.Revoked revoked) { /* Map below, including failures consumed by handlers. */ }
            if (ActorTransactionDataSource.wasRevoked()) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.resetBuffer();
                AuthHttp.error(response, 401, "UNAUTHENTICATED", "Authentication required");
            } else buffered.copyBodyToResponse();
        } finally { ActorTransactionDataSource.clearRevocation(); }
    }
    @FunctionalInterface private interface RoleRequest { void run(HttpServletResponse response) throws IOException; }

    private static ChecklistDraftStore.Actor checklistActor() {
        AuthProvider.CurrentUser user = (AuthProvider.CurrentUser)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        ChecklistDraftStore.Role role = switch (user.role()) {
            case "REPORTER" -> ChecklistDraftStore.Role.REPORTER;
            case "DEVELOPER" -> ChecklistDraftStore.Role.DEVELOPER;
            case "MAINTAINER" -> ChecklistDraftStore.Role.MAINTAINER;
            default -> throw new IllegalStateException("Authenticated account has an unsupported role");
        };
        return new ChecklistDraftStore.Actor(user.id(), role, true);
    }

    private boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        if (!publicOrigin.isBlank() && publicOrigin.equals(origin)) return true;
        try {
            URI parsed = URI.create(origin);
            return parsed.getHost() != null && parsed.getHost().equalsIgnoreCase(request.getServerName())
                    && effectivePort(parsed) == request.getServerPort()
                    && Objects.equals(parsed.getScheme(), request.getScheme());
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean emptyRequestShape(HttpServletRequest request) throws IOException {
        return request.getQueryString() == null && request.getInputStream().readNBytes(1).length == 0;
    }

    private static Object badRequest(HttpServletResponse response) throws IOException {
        AuthHttp.error(response, 400, "VALIDATION_FAILED", "Invalid authentication request");
        return null;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
    }

    private static Map<String, Object> userJson(AuthProvider.CurrentUser user) {
        return Map.of("id", user.id().toString(), "displayName", user.displayName(),
                "role", user.role(), "active", true);
    }
}
