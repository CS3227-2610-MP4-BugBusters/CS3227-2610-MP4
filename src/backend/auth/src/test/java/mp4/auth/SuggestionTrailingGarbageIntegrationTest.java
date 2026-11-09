package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mp4.platform.AuthApplication;
import mp4.platform.SuggestionProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Real-session regression for rejecting provider output with trailing non-JSON bytes. */
class SuggestionTrailingGarbageIntegrationTest {
    private static final String PROVIDER_OUTPUT = "[\"Advisory step\"] trailing garbage";
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
        @Override public ProviderResponse suggest(String title, String description) {
            calls.incrementAndGet();
            return new ProviderResponse(PROVIDER_OUTPUT, null);
        }
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

    @Test void validSuggestionArrayFollowedByGarbageIsRejectedWithoutMutation() throws Exception {
        PROVIDER.calls.set(0);
        UUID developer = account("DEVELOPER");
        UUID reporter = account("REPORTER");
        UUID report = UUID.randomUUID();
        fixture(report, reporter, developer);
        Snapshot before = snapshot(report);
        Browser browser = new Browser();
        login(browser, developer);
        String csrf = csrf(browser);

        HttpResponse<String> response = browser.request("POST",
            "/api/reports/" + report + "/checklist/suggestions", "{}",
            "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);

        assertEquals(502, response.statusCode(), "malformed provider output status");
        Map<String, Object> error = SessionAuthenticationTest.json(response);
        assertEquals("AI_INVALID_OUTPUT", error.get("code"));
        assertFalse(response.body().contains("Advisory step"), "provider steps must not be returned");
        assertFalse(response.body().contains(PROVIDER_OUTPUT), "raw provider output must not be returned");
        assertEquals(1, PROVIDER.calls.get(), "authorized request invokes provider once");
        assertEquals(before, snapshot(report), "rejected generation must not mutate report or checklist state");
    }

    private static final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> request(String method, String path, String body, String... headers) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(base + path));
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
        String password;
        try (var c = connection(); var q = c.prepareStatement("SELECT username FROM app_accounts WHERE id=?")) {
            q.setObject(1, id);
            try (var row = q.executeQuery()) { assertTrue(row.next()); username = row.getString(1); }
        }
        password = passwords.get(id);
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
        UUID id = UUID.randomUUID(); String username = "trailing-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString(); passwords.put(id, password);
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO app_accounts(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)")) {
            insert.setObject(1, id); insert.setString(2, username); insert.setString(3, "Trailing " + role);
            insert.setString(4, "{bcrypt}" + SessionAuthenticationTest.ENCODER.encode(password));
            insert.setString(5, role); insert.executeUpdate();
        }
        return id;
    }

    private static void fixture(UUID report, UUID reporter, UUID assignee) throws Exception {
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?, ?,?,'ASSIGNED')")) {
            insert.setObject(1, report); insert.setString(2, "Synthetic title");
            insert.setString(3, "Synthetic description"); insert.setObject(4, reporter); insert.setObject(5, assignee);
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
