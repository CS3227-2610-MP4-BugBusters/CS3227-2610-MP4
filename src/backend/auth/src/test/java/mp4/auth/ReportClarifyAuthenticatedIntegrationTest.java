package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mp4.platform.AuthApplication;
import mp4.platform.ReportClarifyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Real-session, PostgreSQL integration coverage for who may call POST /api/reporter/report-clarify
 * (RC-02a, RC-02b). Needs MP4_AUTH_TEST_JDBC_URL, MP4_AUTH_TEST_DB_USER and MP4_AUTH_TEST_DB_PASSWORD.
 */
class ReportClarifyAuthenticatedIntegrationTest {
    private static final String PATH = "/api/reporter/report-clarify";
    private static final String BODY = "{\"title\":\"Button\",\"description\":\"### What happened?\\nbutton\"}";
    private static final AtomicInteger PROVIDER_CALLS = new AtomicInteger();
    private static ConfigurableApplicationContext context;
    private static String base;

    @Configuration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean @Primary ReportClarifyProvider reportClarifyProvider() {
            return new ReportClarifyProvider() {
                @Override public String clarify(UUID actorId, String title, String description) {
                    PROVIDER_CALLS.incrementAndGet();
                    return "{\"unclear\":[{\"quote\":\"button\",\"question\":\"Which button?\"}],\"missing\":[]}";
                }
                @Override public String modelName() { return "test-model"; }
            };
        }
    }

    record Account(UUID id, String username, String password) { }

    static final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();

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
        HttpResponse<String> login(Account account) throws Exception {
            return request("POST", "/api/auth/login", "username=" + enc(account.username())
                + "&password=" + enc(account.password()), "Content-Type", "application/x-www-form-urlencoded",
                "X-CSRF-TOKEN", csrf());
        }
        HttpResponse<String> clarify(String csrf) throws Exception {
            return csrf == null
                ? request("POST", PATH, BODY, "Content-Type", "application/json")
                : request("POST", PATH, BODY, "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
        }
    }

    @BeforeAll static void start() {
        var db = ProvisioningDatabase.Config.fromEnvironment(Map.of(
            "MP4_AUTH_JDBC_URL", System.getenv("MP4_AUTH_TEST_JDBC_URL"),
            "MP4_AUTH_DB_USER", System.getenv("MP4_AUTH_TEST_DB_USER"),
            "MP4_AUTH_DB_PASSWORD", System.getenv("MP4_AUTH_TEST_DB_PASSWORD")));
        ProvisioningDatabase.initialize(db);
        context = new SpringApplicationBuilder(AuthApplication.class,
                SessionAuthenticationTest.TestClockConfiguration.class, ProviderConfiguration.class)
            .properties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                "spring.main.banner-mode", "off", "logging.level.root", "ERROR",
                "mp4.auth.local", "true", "mp4.auth.jdbc-url", db.url(),
                "mp4.auth.db-user", db.user(), "mp4.auth.db-password", db.password(),
                "mp4.auth.rate-limit-secret", UUID.randomUUID().toString() + UUID.randomUUID()))
            .run();
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll static void stop() { if (context != null) context.close(); }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private static Account account(String role) throws Exception {
        Account a = new Account(UUID.randomUUID(), "clarify-" + UUID.randomUUID(), UUID.randomUUID().toString());
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO app_accounts(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)")) {
            insert.setObject(1, a.id()); insert.setString(2, a.username()); insert.setString(3, "Clarify " + role);
            insert.setString(4, "{bcrypt}" + SessionAuthenticationTest.ENCODER.encode(a.password()));
            insert.setString(5, role); insert.executeUpdate();
        }
        return a;
    }

    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(System.getenv("MP4_AUTH_TEST_JDBC_URL"),
            System.getenv("MP4_AUTH_TEST_DB_USER"), System.getenv("MP4_AUTH_TEST_DB_PASSWORD"));
    }

    private static Browser loggedIn(Account account) throws Exception {
        Browser browser = new Browser();
        SessionAuthenticationTest.status(200, browser.login(account), "valid login");
        return browser;
    }

    private static void assertSafeRefusal(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertFalse(response.body().contains("Button"));
        assertFalse(response.body().contains("What happened"));
    }

    @Test void rc02_positiveControlActiveReporterWithCsrfIsServed() throws Exception {
        PROVIDER_CALLS.set(0);
        Browser browser = loggedIn(account("REPORTER"));
        var response = browser.clarify(browser.csrf());
        SessionAuthenticationTest.status(200, response, "active Reporter check");
        assertEquals(1, PROVIDER_CALLS.get());
        assertTrue(SessionAuthenticationTest.json(response).containsKey("unclear"));
    }

    @Test void rc02a_unauthenticatedCallerIsRefusedWithoutProvider() throws Exception {
        PROVIDER_CALLS.set(0);
        Browser anonymous = new Browser();
        assertSafeRefusal(anonymous.clarify(anonymous.csrf()), 401);
        assertEquals(0, PROVIDER_CALLS.get());
    }

    @Test void rc02a_developerAndMaintainerAreRefusedWithoutProvider() throws Exception {
        PROVIDER_CALLS.set(0);
        for (String role : new String[] {"DEVELOPER", "MAINTAINER"}) {
            Browser browser = loggedIn(account(role));
            assertSafeRefusal(browser.clarify(browser.csrf()), 403);
        }
        assertEquals(0, PROVIDER_CALLS.get());
    }

    @Test void rc02a_inactiveReporterIsRefusedWithoutProvider() throws Exception {
        PROVIDER_CALLS.set(0);
        Account reporter = account("REPORTER");
        Browser browser = loggedIn(reporter);
        try (var c = connection(); var update = c.prepareStatement(
                "UPDATE app_accounts SET active=false WHERE id=?")) {
            update.setObject(1, reporter.id()); update.executeUpdate();
        }
        var response = browser.clarify(browser.csrf());
        assertTrue(response.statusCode() == 401 || response.statusCode() == 403, response.body());
        assertSafeRefusal(response, response.statusCode());
        assertEquals(0, PROVIDER_CALLS.get());
    }

    @Test void rc02b_missingOrWrongCsrfTokenIsRefusedWithoutProvider() throws Exception {
        PROVIDER_CALLS.set(0);
        Browser browser = loggedIn(account("REPORTER"));
        assertSafeRefusal(browser.clarify(null), 403);
        assertSafeRefusal(browser.clarify("not-a-valid-token"), 403);
        assertEquals(0, PROVIDER_CALLS.get());
    }
}
