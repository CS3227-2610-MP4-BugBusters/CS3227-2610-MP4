package mp4.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Authorized, read-only boundary around report-grounded suggestions. */
@Service
final class SuggestionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SuggestionService.class);
    private static final String SECURITY_CONTEXT = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    private static final String CREATED = AuthSessionFreshnessFilter.CREATED;
    private static final String LAST = AuthSessionFreshnessFilter.LAST;
    private static final Duration ABSOLUTE_SESSION_LIMIT = Duration.ofHours(8);
    private static final Duration IDLE_SESSION_LIMIT = Duration.ofMinutes(30);
    private static final Duration PROVIDER_DEADLINE = Duration.ofSeconds(15);
    private static final int TITLE_LIMIT = 512;
    private static final int DESCRIPTION_LIMIT = 8192;
    private static final int CHECKLIST_STEP_LIMIT = 20;
    private static final int CHECKLIST_BYTES_LIMIT = 8192;

    private final DataSource dataSource;
    private final Clock clock;
    private final ObjectMapper json;
    private final SuggestionProvider provider;
    private final SessionRepository<? extends Session> sessions;
    private final Semaphore inFlight = new Semaphore(1);
    private final ExecutorService providerWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "suggestion-provider");
        thread.setDaemon(true);
        return thread;
    });

    @SuppressWarnings("unchecked")
    SuggestionService(DataSource dataSource, Clock clock,
            ObjectProvider<SuggestionProvider> providers,
            ObjectProvider<SessionRepository> sessionRepositories) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.provider = providers.getIfAvailable();
        this.sessions = (SessionRepository<? extends Session>) sessionRepositories.getIfAvailable();
    }

    void suggest(UUID reportId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        AuthProvider.CurrentUser actor = currentDeveloper();
        HttpSession httpSession = request.getSession(false);
        if (actor == null || httpSession == null || sessions == null) {
            error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        String sessionId = httpSession.getId();
        Long createdAt = (Long) httpSession.getAttribute(CREATED);
        Long lastAt = (Long) httpSession.getAttribute(LAST);
        if (!validSessionTimes(createdAt, lastAt, clock.millis())) {
            error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }

        final Snapshot source;
        try {
            source = snapshot(reportId, actor);
        } catch (SQLExceptionFailure unavailable) {
            error(response, 503, "STORAGE_UNAVAILABLE", "Suggestion generation is temporarily unavailable");
            return;
        }
        // The request may have waited on a database lock while its session was invalidated.
        if (!freshSession(sessionId, actor, createdAt, clock.millis())) {
            error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        if (source == null) {
            error(response, 403, "FORBIDDEN", "Access denied");
            return;
        }
        if (source.oversized || utf8Length(source.title) > TITLE_LIMIT
                || utf8Length(source.description) > DESCRIPTION_LIMIT
                || source.checklistOversized || !validUnicode(source.title)
                || !validUnicode(source.description)
                || source.checklist.stream().anyMatch(step -> !validUnicode(step))) {
            error(response, 400, "INPUT_TOO_LARGE", "Report context exceeds the suggestion input limit");
            return;
        }
        if (provider == null) {
            recordTelemetry("unavailable", System.nanoTime(), null);
            error(response, 503, "AI_UNAVAILABLE", "Suggestions are temporarily unavailable");
            return;
        }
        long providerStarted = System.nanoTime();
        if (!inFlight.tryAcquire()) {
            recordTelemetry("busy", providerStarted, null);
            error(response, 429, "AI_BUSY", "Suggestion capacity is currently full");
            return;
        }

        Future<SuggestionProvider.ProviderResponse> pending;
        try {
            pending = providerWorker.submit(() -> {
                try {
                    return provider.suggest(source.title, source.description, source.checklist);
                } finally {
                    inFlight.release();
                }
            });
        } catch (RuntimeException unavailable) {
            inFlight.release();
            recordTelemetry("unavailable", providerStarted, null);
            error(response, 503, "AI_UNAVAILABLE", "Suggestions are temporarily unavailable");
            return;
        }

        final SuggestionProvider.ProviderResponse generated;
        try {
            generated = pending.get(PROVIDER_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            recordTelemetry("timeout", providerStarted, null);
            error(response, 504, "AI_TIMEOUT", "Suggestion generation timed out");
            return;
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            recordTelemetry("unavailable", providerStarted, null);
            error(response, 503, "AI_UNAVAILABLE", "Suggestions are temporarily unavailable");
            return;
        } catch (ExecutionException failed) {
            SuggestionProvider.Failure providerFailure = providerFailure(failed.getCause());
            if (providerFailure != null) {
                switch (providerFailure.kind()) {
                    case TIMEOUT -> {
                        recordTelemetry("timeout", providerStarted, null);
                        error(response, 504, "AI_TIMEOUT", "Suggestion generation timed out");
                    }
                    case INVALID -> {
                        recordTelemetry("invalid_output", providerStarted, null);
                        error(response, 502, "AI_INVALID_OUTPUT", "The suggestion provider returned an invalid response");
                    }
                    case BUSY -> {
                        recordTelemetry("busy", providerStarted, null);
                        error(response, 429, "AI_BUSY", "Suggestion capacity is currently full");
                    }
                    case UNAVAILABLE -> {
                        recordTelemetry("unavailable", providerStarted, null);
                        error(response, 503, "AI_UNAVAILABLE", "Suggestions are temporarily unavailable");
                    }
                }
                return;
            }
            recordTelemetry("unavailable", providerStarted, null);
            error(response, 503, "AI_UNAVAILABLE", "Suggestions are temporarily unavailable");
            return;
        }

        final List<String> steps;
        try {
            steps = validateOutput(generated == null ? null : generated.rawOutput());
        } catch (IllegalArgumentException invalid) {
            recordTelemetry("invalid_output", providerStarted, generated == null ? null : generated.usage());
            error(response, 502, "AI_INVALID_OUTPUT", "The suggestion provider returned an invalid response");
            return;
        }

        if (!freshSession(sessionId, actor, createdAt, clock.millis())) {
            recordTelemetry("unauthenticated", providerStarted, generated == null ? null : generated.usage());
            error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        final Snapshot current;
        try {
            current = snapshot(reportId, actor);
        } catch (SQLExceptionFailure unavailable) {
            recordTelemetry("storage_unavailable", providerStarted, generated == null ? null : generated.usage());
            error(response, 503, "STORAGE_UNAVAILABLE", "Suggestion generation is temporarily unavailable");
            return;
        }
        // Repeat the persisted-session check after the final lock wait, immediately before delivery.
        if (!freshSession(sessionId, actor, createdAt, clock.millis())) {
            recordTelemetry("unauthenticated", providerStarted, generated == null ? null : generated.usage());
            error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        if (current == null || !source.equals(current)) {
            recordTelemetry("stale_source", providerStarted, generated == null ? null : generated.usage());
            error(response, 409, "STALE_SOURCE", "Report access or content changed during suggestion generation");
            return;
        }

        recordTelemetry("success", providerStarted, generated == null ? null : generated.usage());
        response.setStatus(200);
        response.setContentType("application/json; charset=utf-8");
        json.writeValue(response.getOutputStream(), new SuggestionResult(steps, source.version, source.draftRevision));
    }

    private Snapshot snapshot(UUID reportId, AuthProvider.CurrentUser actor) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement account = connection.prepareStatement("""
                        SELECT role, active, auth_generation FROM public.app_accounts WHERE id = ? FOR UPDATE
                        """)) {
                    account.setObject(1, actor.id());
                    try (ResultSet row = account.executeQuery()) {
                        if (!row.next() || !row.getBoolean("active") || !"DEVELOPER".equals(row.getString("role"))
                                || row.getLong("auth_generation") != actor.generation()) {
                            connection.commit();
                            return null;
                        }
                    }
                }
                Snapshot reportSnapshot;
                try (PreparedStatement report = connection.prepareStatement("""
                        SELECT CASE WHEN octet_length(convert_to(title, 'UTF8')) <= ? THEN title END AS title,
                               CASE WHEN octet_length(convert_to(description, 'UTF8')) <= ? THEN description END AS description,
                               octet_length(convert_to(title, 'UTF8')) AS title_bytes,
                               octet_length(convert_to(description, 'UTF8')) AS description_bytes,
                               assignee_id, version
                        FROM public.reports WHERE id = ? FOR UPDATE
                        """)) {
                    report.setInt(1, TITLE_LIMIT);
                    report.setInt(2, DESCRIPTION_LIMIT);
                    report.setObject(3, reportId);
                    try (ResultSet row = report.executeQuery()) {
                        if (!row.next() || !actor.id().equals(row.getObject("assignee_id", UUID.class))) {
                            connection.commit();
                            return null;
                        }
                        boolean oversized = row.getInt("title_bytes") > TITLE_LIMIT
                                || row.getInt("description_bytes") > DESCRIPTION_LIMIT;
                        reportSnapshot = new Snapshot(reportId, row.getString("title"), row.getString("description"),
                                row.getLong("version"), List.of(), 0, oversized, false);
                    }
                }
                // Read the draft in a separate READ COMMITTED statement after locking the report.
                // Save also locks the report, so this sees the latest committed draft without
                // transferring an unbounded text[] into the application.
                try (PreparedStatement checklist = connection.prepareStatement("""
                        SELECT COALESCE(c.draft_revision, 0) AS draft_revision,
                               CASE WHEN c.report_id IS NULL THEN FALSE
                                    WHEN cardinality(c.draft_steps) > ? THEN TRUE
                                    WHEN array_position(c.draft_steps, NULL) IS NOT NULL THEN TRUE
                                    WHEN (SELECT COALESCE(sum(octet_length(convert_to(step.value, 'UTF8'))), 0)
                                          FROM unnest(c.draft_steps) AS step(value)) > ? THEN TRUE
                                    ELSE FALSE END AS oversized,
                               CASE WHEN c.report_id IS NULL THEN ARRAY[]::text[]
                                    WHEN cardinality(c.draft_steps) > ? THEN ARRAY[]::text[]
                                    WHEN array_position(c.draft_steps, NULL) IS NOT NULL THEN ARRAY[]::text[]
                                    WHEN (SELECT COALESCE(sum(octet_length(convert_to(step.value, 'UTF8'))), 0)
                                          FROM unnest(c.draft_steps) AS step(value)) > ? THEN ARRAY[]::text[]
                                    ELSE c.draft_steps END AS draft_steps
                        FROM (SELECT ?::uuid AS report_id) requested
                        LEFT JOIN public.investigation_checklists c ON c.report_id = requested.report_id
                        """)) {
                    checklist.setInt(1, CHECKLIST_STEP_LIMIT);
                    checklist.setInt(2, CHECKLIST_BYTES_LIMIT);
                    checklist.setInt(3, CHECKLIST_STEP_LIMIT);
                    checklist.setInt(4, CHECKLIST_BYTES_LIMIT);
                    checklist.setObject(5, reportId);
                    try (ResultSet row = checklist.executeQuery()) {
                        row.next();
                        java.sql.Array draftArray = row.getArray("draft_steps");
                        List<String> draft;
                        try {
                            draft = List.of((String[]) draftArray.getArray());
                        } finally {
                            draftArray.free();
                        }
                        reportSnapshot = new Snapshot(reportSnapshot.id, reportSnapshot.title,
                                reportSnapshot.description, reportSnapshot.version, draft,
                                row.getLong("draft_revision"), reportSnapshot.oversized,
                                row.getBoolean("oversized"));
                    }
                }
                Snapshot result = reportSnapshot;
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new SQLExceptionFailure(failure);
        }
    }

    private boolean freshSession(String sessionId, AuthProvider.CurrentUser actor, Long initialCreated, long now) {
        try {
            Session stored = sessions.findById(sessionId);
            if (stored == null || stored.isExpired()) return false;
            Long created = stored.getAttribute(CREATED);
            Long last = stored.getAttribute(LAST);
            if (created == null || !created.equals(initialCreated) || !validSessionTimes(created, last, now)) return false;
            SecurityContext context = stored.getAttribute(SECURITY_CONTEXT);
            Authentication authentication = context == null ? null : context.getAuthentication();
            return authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof AuthProvider.CurrentUser current
                    && current.id().equals(actor.id()) && current.generation() == actor.generation()
                    && "DEVELOPER".equals(current.role());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean validSessionTimes(Long created, Long last, long now) {
        return created != null && last != null && created <= now && last <= now
                && now - created < ABSOLUTE_SESSION_LIMIT.toMillis()
                && now - last < IDLE_SESSION_LIMIT.toMillis();
    }

    private static SuggestionProvider.Failure providerFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SuggestionProvider.Failure providerFailure) return providerFailure;
        }
        return null;
    }

    private void recordTelemetry(String outcome, long startedAt, SuggestionProvider.Usage usage) {
        String model = "unavailable";
        try {
            if (provider != null) {
                String selectedModel = provider.modelName();
                if (selectedModel != null && selectedModel.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}")) {
                    model = selectedModel;
                }
            }
        } catch (RuntimeException ignored) {
            // Telemetry metadata must not change the suggestion result or expose exception details.
        }
        long durationMillis = Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        try {
            LOGGER.info("event=developer_ai_suggestion outcome={} model={} duration_ms={} input_tokens={} output_tokens={} timeout_ms={} in_flight_limit={}",
                    outcome, model, durationMillis, usageValue(usage == null ? null : usage.inputTokens()),
                    usageValue(usage == null ? null : usage.outputTokens()), PROVIDER_DEADLINE.toMillis(), 1);
        } catch (RuntimeException ignored) {
            // Logging is best effort; the request must retain its application-level outcome.
        }
    }

    private static String usageValue(Long value) {
        return value == null || value < 0 ? "unavailable" : value.toString();
    }

    private static AuthProvider.CurrentUser currentDeveloper() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthProvider.CurrentUser actor)
                || !"DEVELOPER".equals(actor.role())) return null;
        return actor;
    }

    private List<String> validateOutput(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 64 * 1024 || !validUnicode(raw)) {
            throw new IllegalArgumentException("invalid output");
        }
        List<String> steps = new ArrayList<>();
        String normalized = SocSuggestionProvider.normalizeSeparatedJsonStrings(raw);
        try (var parser = json.getFactory().createParser(normalized)) {
            com.fasterxml.jackson.core.JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token != com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
                    throw new IllegalArgumentException("invalid output");
                }
                int arraySize = 0;
                while ((token = parser.nextToken()) != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                    if (token != com.fasterxml.jackson.core.JsonToken.VALUE_STRING || steps.size() >= 8) {
                        throw new IllegalArgumentException("invalid output");
                    }
                    String text = parser.getText();
                    if (text.isBlank() || text.indexOf('\0') >= 0 || text.indexOf('\r') >= 0
                            || text.indexOf('\n') >= 0 || !validUnicode(text) || utf8Length(text) > 300) {
                        throw new IllegalArgumentException("invalid output");
                    }
                    steps.add(text);
                    arraySize++;
                }
                if (arraySize == 0) throw new IllegalArgumentException("invalid output");
            }
            if (steps.isEmpty()) throw new IllegalArgumentException("invalid output");
            return List.copyOf(steps);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("invalid output");
        }
    }

    private static int utf8Length(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static boolean validUnicode(String value) {
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            return true;
        } catch (CharacterCodingException invalid) {
            return false;
        }
    }

    private static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        AuthHttp.error(response, status, code, message);
    }

    private record Snapshot(UUID id, String title, String description, long version, List<String> checklist,
            long draftRevision, boolean oversized, boolean checklistOversized) { }
    private record SuggestionResult(List<String> steps, long sourceVersion, long draftRevision) { }
    private static final class SQLExceptionFailure extends RuntimeException {
        SQLExceptionFailure(Throwable cause) { super(cause); }
    }
}
