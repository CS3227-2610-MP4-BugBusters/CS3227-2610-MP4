package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import mp4.platform.AuthApplication;
import mp4.platform.SuggestionProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Real-session integration coverage for typed provider failures. */
class SuggestionProviderFailureIntegrationTest {
    private static final String PROVIDER_BODY = "provider-body-canary";
    private static final String PROVIDER_SECRET = "provider-secret-canary";
    private static final FakeProvider PROVIDER = new FakeProvider();
    private static ProvisioningDatabase.Config db;
    private static ConfigurableApplicationContext app;
    private static String base;

    @Configuration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean @Primary SuggestionProvider suggestionProvider() { return PROVIDER; }
    }

    static final class FakeProvider implements SuggestionProvider {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<FailureKind> failure = new AtomicReference<>();

        @Override public ProviderResponse suggest(String title, String description) {
            calls.incrementAndGet();
            throw new Failure(failure.get(), PROVIDER_BODY + " " + PROVIDER_SECRET);
        }
    }

    record FailureCase(SuggestionProvider.FailureKind kind, int status, String code) { }

    static Stream<FailureCase> failures() {
        return Stream.of(
            new FailureCase(SuggestionProvider.FailureKind.UNAVAILABLE, 503, "AI_UNAVAILABLE"),
            new FailureCase(SuggestionProvider.FailureKind.TIMEOUT, 504, "AI_TIMEOUT"),
            new FailureCase(SuggestionProvider.FailureKind.INVALID, 502, "AI_INVALID_OUTPUT"),
            new FailureCase(SuggestionProvider.FailureKind.BUSY, 429, "AI_BUSY"));
    }

    @BeforeAll static void startServer() {
        db = ProvisioningDatabase.Config.fromEnvironment(Map.of(
            "MP4_AUTH_JDBC_URL", System.getenv("MP4_AUTH_TEST_JDBC_URL"),
            "MP4_AUTH_DB_USER", System.getenv("MP4_AUTH_TEST_DB_USER"),
            "MP4_AUTH_DB_PASSWORD", System.getenv("MP4_AUTH_TEST_DB_PASSWORD")));
        ProvisioningDatabase.initialize(db);
        app = new SpringApplicationBuilder(AuthApplication.class,
                SessionAuthenticationTest.TestClockConfiguration.class, ProviderConfiguration.class)
            .properties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                "spring.main.banner-mode", "off", "logging.level.root", "ERROR",
                "mp4.auth.local", "true", "mp4.auth.jdbc-url", db.url(),
                "mp4.auth.db-user", db.user(), "mp4.auth.db-password", db.password(),
                "mp4.auth.rate-limit-secret", UUID.randomUUID().toString() + UUID.randomUUID()))
            .run();
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll static void stopServer() { if (app != null) app.close(); }

    @ParameterizedTest
    @MethodSource("failures")
    void typedProviderFailureIsSanitizedAndDoesNotMutateReport(FailureCase failure) throws Exception {
        PROVIDER.failure.set(failure.kind());
        PROVIDER.calls.set(0);
        UUID developer = account("DEVELOPER");
        UUID reporter = account("REPORTER");
        UUID report = UUID.randomUUID();
        fixture(report, reporter, developer);
        Snapshot before = snapshot(report);
        Browser browser = new Browser();
        login(browser, developer);

        HttpResponse<String> response = browser.request("POST",
            "/api/reports/" + report + "/checklist/suggestions", "{}",
            "Content-Type", "application/json", "X-CSRF-TOKEN", csrf(browser));

        String responseBody = response.body();
        Map<String, Object> error = Map.of();
        RuntimeException jsonFailure = null;
        try {
            error = SessionAuthenticationTest.json(response);
        } catch (RuntimeException invalidJson) {
            jsonFailure = invalidJson;
        }
        Map<String, Object> observedError = error;
        RuntimeException observedJsonFailure = jsonFailure;
        int providerCalls = PROVIDER.calls.get();
        Snapshot after = snapshot(report);

        assertAll("typed provider failure remains safe and does not mutate domain state",
            () -> assertEquals(failure.status(), response.statusCode(), "typed provider failure status"),
            () -> assertNull(observedJsonFailure, "error response must contain valid JSON"),
            () -> assertEquals(failure.code(), observedError.get("code"), "safe provider error code"),
            () -> assertTrue(observedError.get("requestId") instanceof String
                    && !((String) observedError.get("requestId")).isBlank(), "request ID is present"),
            () -> assertFalse(responseBody.contains(PROVIDER_BODY), "provider body must not be returned"),
            () -> assertFalse(responseBody.contains(PROVIDER_SECRET), "provider secret must not be returned"),
            () -> assertFalse(responseBody.contains("report-title-canary"), "report title must not be returned on failure"),
            () -> assertFalse(responseBody.contains("report-description-canary"), "report description must not be returned on failure"),
            () -> assertEquals(1, providerCalls, "authorized request invokes provider once"),
            () -> assertEquals(before, after, "provider failure must not mutate report or checklist state"));
    }

    private static final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> request(String method, String path, String body, String... headers) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
            for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
            return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        }
    }

    private record Snapshot(String title, String description, UUID assignee, long version,
            String[] draft, long draftRevision, String[] shared) {
        @Override public boolean equals(Object other) {
            if (!(other instanceof Snapshot that)) return false;
            return title.equals(that.title) && description.equals(that.description)
                && assignee.equals(that.assignee) && version == that.version
                && java.util.Arrays.equals(draft, that.draft) && draftRevision == that.draftRevision
                && java.util.Arrays.equals(shared, that.shared);
        }
        @Override public int hashCode() { return 0; }
    }

    private static void login(Browser browser, UUID id) throws Exception {
        String username;
        try (var c = connection(); var q = c.prepareStatement("SELECT username FROM app_accounts WHERE id=?")) {
            q.setObject(1, id);
            try (var row = q.executeQuery()) { assertTrue(row.next()); username = row.getString(1); }
        }
        String password = passwords.get(id);
        var loggedIn = browser.request("POST", "/api/auth/login",
            "username=" + encode(username) + "&password=" + encode(password),
            "Content-Type", "application/x-www-form-urlencoded", "X-CSRF-TOKEN", csrf(browser));
        SessionAuthenticationTest.status(200, loggedIn, "valid login");
    }

    private static String csrf(Browser browser) throws Exception {
        var response = browser.request("GET", "/api/auth/csrf", "");
        SessionAuthenticationTest.status(200, response, "CSRF bootstrap");
        return (String) SessionAuthenticationTest.json(response).get("token");
    }

    private static final Map<UUID, String> passwords = new java.util.concurrent.ConcurrentHashMap<>();
    private static UUID account(String role) throws Exception {
        UUID id = UUID.randomUUID();
        String username = "provider-failure-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        passwords.put(id, password);
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO app_accounts(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)")) {
            insert.setObject(1, id); insert.setString(2, username); insert.setString(3, "Failure " + role);
            insert.setString(4, "{bcrypt}" + SessionAuthenticationTest.ENCODER.encode(password));
            insert.setString(5, role); insert.executeUpdate();
        }
        return id;
    }

    private static void fixture(UUID report, UUID reporter, UUID assignee) throws Exception {
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?, ?,?,'ASSIGNED')")) {
            insert.setObject(1, report); insert.setString(2, "report-title-canary");
            insert.setString(3, "report-description-canary"); insert.setObject(4, reporter); insert.setObject(5, assignee);
            insert.executeUpdate();
        }
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?::text[],4,?::text[])")) {
            insert.setObject(1, report); insert.setString(2, "{draft canary}");
            insert.setString(3, "{shared canary}"); insert.executeUpdate();
        }
    }

    private static Snapshot snapshot(UUID report) throws Exception {
        try (var c = connection(); var q = c.prepareStatement(
                "SELECT r.title,r.description,r.assignee_id,r.version,c.draft_steps,c.draft_revision,c.shared_steps "
                    + "FROM reports r JOIN investigation_checklists c ON c.report_id=r.id WHERE r.id=?")) {
            q.setObject(1, report);
            try (var row = q.executeQuery()) {
                assertTrue(row.next());
                return new Snapshot(row.getString(1), row.getString(2), row.getObject(3, UUID.class), row.getLong(4),
                    (String[]) row.getArray(5).getArray(), row.getLong(6), (String[]) row.getArray(7).getArray());
            }
        }
    }

    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(db.url(), db.user(), db.password());
    }
    private static String encode(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
