package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Real-session coverage for logout racing both blocking suggestion snapshots. */
class SuggestionLogoutSnapshotRaceIntegrationTest {
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    private static final BlockingProvider PROVIDER = new BlockingProvider();
    private static TestServer server;

    @Configuration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean @Primary SuggestionProvider suggestionProvider() { return PROVIDER; }
    }

    static final class TestServer implements AutoCloseable {
        final ConfigurableApplicationContext context;
        final String base;

        TestServer(ProvisioningDatabase.Config db) {
            context = new SpringApplicationBuilder(AuthApplication.class,
                    SessionAuthenticationTest.TestClockConfiguration.class, ProviderConfiguration.class)
                .properties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                    "spring.main.banner-mode", "off", "logging.level.root", "ERROR",
                    "mp4.auth.local", "true", "mp4.auth.jdbc-url", db.url(),
                    "mp4.auth.db-user", db.user(), "mp4.auth.db-password", db.password(),
                    "mp4.auth.rate-limit-secret", UUID.randomUUID() + UUID.randomUUID().toString()))
                .run();
            base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        }

        @Override public void close() { context.close(); }
    }

    static final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> request(String method, String path, String body, String... headers) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(server.base + path)).timeout(Duration.ofSeconds(20));
            for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
            return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        }

        String csrf() throws Exception {
            var response = request("GET", "/api/auth/csrf", "");
            assertEquals(200, response.statusCode(), "CSRF bootstrap");
            return (String) SessionAuthenticationTest.json(response).get("token");
        }

        HttpResponse<String> login(Account account) throws Exception {
            return request("POST", "/api/auth/login", "username=" + encode(account.username())
                    + "&password=" + encode(account.password()), "Content-Type", "application/x-www-form-urlencoded",
                    "X-CSRF-TOKEN", csrf());
        }

        HttpResponse<String> logout() throws Exception {
            return request("POST", "/api/auth/logout", "", "X-CSRF-TOKEN", csrf());
        }
    }

    record Account(UUID id, String username, String password) { }
    record DomainSnapshot(String title, String description, UUID assignee, String status,
            long version, String[] draft, long draftRevision, String[] shared) { }

    static final class BlockingProvider implements SuggestionProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        void reset(boolean block) {
            calls.set(0);
            entered = block ? new CountDownLatch(1) : null;
            release = block ? new CountDownLatch(1) : null;
        }

        @Override public ProviderResponse suggest(String title, String description) {
            calls.incrementAndGet();
            CountDownLatch started = entered;
            CountDownLatch finish = release;
            if (started != null) {
                started.countDown();
                try {
                    if (!finish.await(10, TimeUnit.SECONDS)) throw new AssertionError("provider release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("provider interrupted", interrupted);
                }
            }
            return new ProviderResponse("[\"Compare the first trace\"]", null);
        }
    }

    @BeforeAll static void startServer() {
        var db = databaseConfig();
        ProvisioningDatabase.initialize(db);
        server = new TestServer(db);
    }

    @AfterAll static void stopServer() { if (server != null) server.close(); }

    @Test void logoutWhileInitialSnapshotWaitsDoesNotInvokeProvider() throws Exception {
        PROVIDER.reset(false);
        Fixture fixture = fixture();
        Browser browser = authenticated(fixture.developer());
        DomainSnapshot before = snapshot(fixture.report());

        try (ReportRowLock lock = lockReport(fixture.report())) {
            CompletableFuture<HttpResponse<String>> pending = asyncSuggestions(browser, fixture.report());
            awaitReportLockWait(lock.backendPid());

            HttpResponse<String> logoutResponse = browser.logout();
            lock.release();
            HttpResponse<String> response = pending.get(15, TimeUnit.SECONDS);
            int providerCalls = PROVIDER.calls.get();
            String responseBody = response.body();
            DomainSnapshot after = snapshot(fixture.report());

            assertAll("logout during initial snapshot wait",
                () -> assertEquals(204, logoutResponse.statusCode(), "logout succeeds"),
                () -> assertEquals(401, response.statusCode(), "logged-out request must be rejected before generation"),
                () -> assertFalse(responseBody.contains("Compare the first trace"), "suggestion must not be returned"),
                () -> assertFalse(responseBody.contains("Synthetic report title"), "report title must not be returned"),
                () -> assertFalse(responseBody.contains("Synthetic report description"), "report description must not be returned"),
                () -> assertFalse(responseBody.contains("private draft canary"), "private draft must not be returned"),
                () -> assertFalse(responseBody.contains("published canary"), "published checklist must not be returned"),
                () -> assertEquals(0, providerCalls, "provider must not run after logout during initial snapshot"),
                () -> assertSnapshotUnchanged(before, after));
        }
    }

    @Test void logoutWhileFinalSnapshotWaitsRejectsResultWithoutMutation() throws Exception {
        PROVIDER.reset(true);
        Fixture fixture = fixture();
        Browser browser = authenticated(fixture.developer());
        DomainSnapshot before = snapshot(fixture.report());
        CompletableFuture<HttpResponse<String>> pending = asyncSuggestions(browser, fixture.report());
        assertTrue(PROVIDER.entered.await(5, TimeUnit.SECONDS), "provider started after initial authorized snapshot");

        try (ReportRowLock lock = lockReport(fixture.report())) {
            PROVIDER.release.countDown();
            awaitReportLockWait(lock.backendPid());

            HttpResponse<String> logoutResponse = browser.logout();
            lock.release();
            HttpResponse<String> response = pending.get(15, TimeUnit.SECONDS);
            int providerCalls = PROVIDER.calls.get();
            String responseBody = response.body();
            DomainSnapshot after = snapshot(fixture.report());

            assertAll("logout during final snapshot wait",
                () -> assertEquals(204, logoutResponse.statusCode(), "logout succeeds"),
                () -> assertEquals(401, response.statusCode(), "logged-out result must be rejected after final snapshot wait"),
                () -> assertFalse(responseBody.contains("Compare the first trace"), "suggestion must not be returned"),
                () -> assertFalse(responseBody.contains("Synthetic report title"), "report title must not be returned"),
                () -> assertFalse(responseBody.contains("Synthetic report description"), "report description must not be returned"),
                () -> assertFalse(responseBody.contains("private draft canary"), "private draft must not be returned"),
                () -> assertFalse(responseBody.contains("published canary"), "published checklist must not be returned"),
                () -> assertEquals(1, providerCalls, "the provider ran once before logout"),
                () -> assertSnapshotUnchanged(before, after));
        }
    }

    private record Fixture(Account developer, UUID report) { }

    private static Fixture fixture() throws Exception {
        Account developer = account();
        Account reporter = account();
        UUID report = UUID.randomUUID();
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
            insert.setObject(1, report);
            insert.setString(2, "Synthetic report title");
            insert.setString(3, "Synthetic report description");
            insert.setObject(4, reporter.id());
            insert.setObject(5, developer.id());
            insert.executeUpdate();
        }
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?::text[],4,?::text[])")) {
            insert.setObject(1, report);
            insert.setString(2, "{\"private draft canary\"}");
            insert.setString(3, "{\"published canary\"}");
            insert.executeUpdate();
        }
        return new Fixture(developer, report);
    }

    private static Account account() throws Exception {
        Account account = new Account(UUID.randomUUID(), "logout-race-" + UUID.randomUUID(), UUID.randomUUID().toString());
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO app_accounts(id,username,display_name,password_hash,role) VALUES(?,?,?,?, 'DEVELOPER')")) {
            insert.setObject(1, account.id());
            insert.setString(2, account.username());
            insert.setString(3, "Suggestion Developer");
            insert.setString(4, "{bcrypt}" + ENCODER.encode(account.password()));
            insert.executeUpdate();
        }
        return account;
    }

    private static Browser authenticated(Account account) throws Exception {
        Browser browser = new Browser();
        assertEquals(200, browser.login(account).statusCode(), "valid Developer login");
        return browser;
    }

    private static CompletableFuture<HttpResponse<String>> asyncSuggestions(Browser browser, UUID report) throws Exception {
        String csrf = browser.csrf();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return browser.request("POST", "/api/reports/" + report + "/checklist/suggestions", "{}",
                    "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
    }

    private static DomainSnapshot snapshot(UUID report) throws Exception {
        try (var c = connection(); var query = c.prepareStatement(
                "SELECT r.title,r.description,r.assignee_id,r.status,r.version,c.draft_steps,c.draft_revision,c.shared_steps "
                + "FROM reports r JOIN investigation_checklists c ON c.report_id=r.id WHERE r.id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return new DomainSnapshot(rows.getString(1), rows.getString(2), rows.getObject(3, UUID.class),
                    rows.getString(4), rows.getLong(5), (String[]) rows.getArray(6).getArray(), rows.getLong(7),
                    (String[]) rows.getArray(8).getArray());
            }
        }
    }

    private static void assertSnapshotUnchanged(DomainSnapshot expected, DomainSnapshot actual) {
        assertAll("persisted report and checklist state remains unchanged",
            () -> assertEquals(expected.title(), actual.title(), "report title"),
            () -> assertEquals(expected.description(), actual.description(), "report description"),
            () -> assertEquals(expected.assignee(), actual.assignee(), "report assignee"),
            () -> assertEquals(expected.status(), actual.status(), "report status"),
            () -> assertEquals(expected.version(), actual.version(), "report version"),
            () -> assertArrayEquals(expected.draft(), actual.draft(), "private checklist draft"),
            () -> assertEquals(expected.draftRevision(), actual.draftRevision(), "draft revision"),
            () -> assertArrayEquals(expected.shared(), actual.shared(), "shared checklist"));
    }

    private static ReportRowLock lockReport(UUID report) throws Exception {
        Connection connection = connection();
        connection.setAutoCommit(false);
        int backendPid;
        try (var pid = connection.prepareStatement("SELECT pg_backend_pid()")) {
            try (var row = pid.executeQuery()) {
                assertTrue(row.next());
                backendPid = row.getInt(1);
            }
        }
        try (var lock = connection.prepareStatement("SELECT id FROM reports WHERE id=? FOR UPDATE")) {
            lock.setObject(1, report);
            try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
        }
        return new ReportRowLock(connection, backendPid);
    }

    private static void awaitReportLockWait(int lockBackendPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (var c = connection(); var query = c.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity activity
                        WHERE activity.datname = current_database() AND activity.wait_event_type = 'Lock'
                          AND activity.query LIKE '%FROM public.reports WHERE id =%'
                          AND ? = ANY(pg_blocking_pids(activity.pid))
                    )
                    """)) {
                query.setInt(1, lockBackendPid);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    if (rows.getBoolean(1)) return;
                }
            }
            Thread.sleep(20);
        }
        fail("suggestion snapshot never appeared in PostgreSQL lock wait state");
    }

    private static final class ReportRowLock implements AutoCloseable {
        private final Connection connection;
        private final int backendPid;
        private boolean released;

        private ReportRowLock(Connection connection, int backendPid) {
            this.connection = connection;
            this.backendPid = backendPid;
        }

        int backendPid() { return backendPid; }

        void release() throws Exception {
            if (!released) {
                connection.rollback();
                connection.close();
                released = true;
            }
        }

        @Override public void close() throws Exception { release(); }
    }

    private static ProvisioningDatabase.Config databaseConfig() {
        return ProvisioningDatabase.Config.fromEnvironment(Map.of(
            "MP4_AUTH_JDBC_URL", System.getenv("MP4_AUTH_TEST_JDBC_URL"),
            "MP4_AUTH_DB_USER", System.getenv("MP4_AUTH_TEST_DB_USER"),
            "MP4_AUTH_DB_PASSWORD", System.getenv("MP4_AUTH_TEST_DB_PASSWORD")));
    }

    private static Connection connection() throws Exception {
        var db = databaseConfig();
        return DriverManager.getConnection(db.url(), db.user(), db.password());
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
