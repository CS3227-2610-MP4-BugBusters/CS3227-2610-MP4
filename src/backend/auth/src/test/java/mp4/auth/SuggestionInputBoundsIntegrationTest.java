package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import mp4.platform.AuthApplication;
import mp4.platform.SuggestionProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Verifies report-size rejection occurs before full text is returned by JDBC. */
class SuggestionInputBoundsIntegrationTest {
    private static ProvisioningDatabase.Config db;
    private static ConfigurableApplicationContext app;
    private static String base;
    private static final FakeProvider PROVIDER = new FakeProvider();
    private static final List<String> reportStringsReturned = new CopyOnWriteArrayList<>();

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean @Primary SuggestionProvider suggestionProvider() { return PROVIDER; }

        @Bean @Primary DataSource observedDataSource(
                @Value("${mp4.auth.jdbc-url}") String url,
                @Value("${mp4.auth.db-user}") String user,
                @Value("${mp4.auth.db-password}") String password) {
            var delegate = new DriverManagerDataSource(url, user, password);
            delegate.setDriverClassName("org.postgresql.Driver");
            return new ObservingDataSource(delegate);
        }
    }

    static final class ObservingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        ObservingDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override public Connection getConnection() throws java.sql.SQLException {
            return observeConnection(delegate.getConnection());
        }
        @Override public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return observeConnection(delegate.getConnection(username, password));
        }

        private Connection observeConnection(Connection connection) {
            return proxy(Connection.class, connection, (method, args) -> {
                Object value = invoke(connection, method, args);
                if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                        && args[0] instanceof String sql && sql.contains("FROM public.reports")) {
                    return observeStatement((PreparedStatement) value);
                }
                return value;
            });
        }

        private PreparedStatement observeStatement(PreparedStatement statement) {
            return proxy(PreparedStatement.class, statement, (method, args) -> {
                Object value = invoke(statement, method, args);
                if ("executeQuery".equals(method.getName()) && (args == null || args.length == 0)) {
                    return observeRows((ResultSet) value);
                }
                return value;
            });
        }

        private ResultSet observeRows(ResultSet rows) {
            return proxy(ResultSet.class, rows, (method, args) -> {
                Object value = invoke(rows, method, args);
                if ("getString".equals(method.getName()) && args != null && args.length == 1
                        && args[0] instanceof String label
                        && ("title".equalsIgnoreCase(label) || "description".equalsIgnoreCase(label))
                        && value instanceof String text) {
                    reportStringsReturned.add(label.toLowerCase() + ":" + text.length());
                }
                return value;
            });
        }

        private static <T> T proxy(Class<T> type, Object target, Invocation invocation) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> invocation.call(method, args)));
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }

        @FunctionalInterface interface Invocation {
            Object call(java.lang.reflect.Method method, Object[] args) throws Throwable;
        }
    }

    static final class FakeProvider implements SuggestionProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile String title;
        volatile String description;
        @Override public ProviderResponse suggest(String title, String description) {
            calls.incrementAndGet(); this.title = title; this.description = description;
            return new ProviderResponse("[\"Compare the first trace\"]", null);
        }
    }

    @BeforeAll static void startServer() {
        db = ProvisioningDatabase.Config.fromEnvironment(Map.of(
            "MP4_AUTH_JDBC_URL", System.getenv("MP4_AUTH_TEST_JDBC_URL"),
            "MP4_AUTH_DB_USER", System.getenv("MP4_AUTH_TEST_DB_USER"),
            "MP4_AUTH_DB_PASSWORD", System.getenv("MP4_AUTH_TEST_DB_PASSWORD")));
        ProvisioningDatabase.initialize(db);
        app = new SpringApplicationBuilder(AuthApplication.class,
                SessionAuthenticationTest.TestClockConfiguration.class, TestConfiguration.class)
            .properties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                "spring.main.banner-mode", "off", "logging.level.root", "ERROR",
                "mp4.auth.local", "true", "mp4.auth.jdbc-url", db.url(),
                "mp4.auth.db-user", db.user(), "mp4.auth.db-password", db.password(),
                "mp4.auth.rate-limit-secret", UUID.randomUUID().toString() + UUID.randomUUID()))
            .run();
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll static void stopServer() { if (app != null) app.close(); }

    @Test void oversizedContextIsRejectedWithoutReturningFullTextAndExactBoundaryIsSent() throws Exception {
        var developer = account("DEVELOPER");
        var reporter = account("REPORTER");
        UUID oversizedReport = UUID.randomUUID();
        fixture(oversizedReport, reporter.id(), developer.id(), "within title", "x".repeat(8193));
        Browser browser = authenticated(developer);

        reportStringsReturned.clear();
        PROVIDER.calls.set(0);
        var oversized = suggestions(browser, oversizedReport);

        assertAll("oversized report description is screened in PostgreSQL before its value is loaded",
            () -> SessionAuthenticationTest.error(oversized, 400, "INPUT_TOO_LARGE"),
            () -> assertEquals(0, PROVIDER.calls.get(), "oversized context must not reach the provider"),
            () -> assertFalse(reportStringsReturned.stream().anyMatch(value -> value.equals("description:8193")),
                "the full oversized description must not be returned by JDBC"));

        UUID boundaryReport = UUID.randomUUID();
        String boundaryTitle = "t".repeat(512);
        String boundaryDescription = "d".repeat(8192);
        fixture(boundaryReport, reporter.id(), developer.id(), boundaryTitle, boundaryDescription);
        reportStringsReturned.clear();
        PROVIDER.calls.set(0);
        var boundary = suggestions(browser, boundaryReport);

        SessionAuthenticationTest.status(200, boundary, "exactly bounded report context");
        assertEquals(1, PROVIDER.calls.get());
        assertEquals(boundaryTitle, PROVIDER.title);
        assertEquals(boundaryDescription, PROVIDER.description);
        assertTrue(reportStringsReturned.contains("title:512"));
        assertTrue(reportStringsReturned.contains("description:8192"));
    }

    private static HttpResponse<String> suggestions(Browser browser, UUID report) throws Exception {
        String csrf = browser.csrf();
        return browser.request("POST", "/api/reports/" + report + "/checklist/suggestions", "{}",
            "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
    }

    private static Account account(String role) throws Exception {
        var account = new Account(UUID.randomUUID(), "input-bound-" + UUID.randomUUID(), UUID.randomUUID().toString());
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO app_accounts(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)")) {
            insert.setObject(1, account.id); insert.setString(2, account.username);
            insert.setString(3, "Input bound " + role);
            insert.setString(4, "{bcrypt}" + SessionAuthenticationTest.ENCODER.encode(account.password));
            insert.setString(5, role); insert.executeUpdate();
        }
        return account;
    }

    private static void fixture(UUID report, UUID reporter, UUID assignee, String title, String description)
            throws Exception {
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
            insert.setObject(1, report); insert.setString(2, title); insert.setString(3, description);
            insert.setObject(4, reporter); insert.setObject(5, assignee); insert.executeUpdate();
        }
    }

    private static Browser authenticated(Account account) throws Exception {
        var browser = new Browser();
        var login = browser.request("POST", "/api/auth/login", "username=" + encode(account.username)
                + "&password=" + encode(account.password), "Content-Type", "application/x-www-form-urlencoded",
                "X-CSRF-TOKEN", browser.csrf());
        SessionAuthenticationTest.status(200, login, "Developer login");
        return browser;
    }

    private record Account(UUID id, String username, String password) { }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(db.url(), db.user(), db.password());
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

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
        String csrf() throws Exception {
            var response = request("GET", "/api/auth/csrf", "");
            SessionAuthenticationTest.status(200, response, "CSRF bootstrap");
            return (String) SessionAuthenticationTest.json(response).get("token");
        }
    }
}
