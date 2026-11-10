package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import mp4.platform.AuthApplication;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Real HTTP/security-chain acceptance tests. No mocked account, session or CSRF service. */
class SessionAuthenticationTest {
    static final MutableClock CLOCK = new MutableClock();
    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static ProvisioningDatabase.Config db;
    static Server app;

    @Configuration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean @Primary Clock acceptanceClock() { return CLOCK; }
    }
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
        void advance(Duration duration) { now.updateAndGet(i -> i.plus(duration)); }
    }
    record Account(UUID id, String username, String password, String role) {}
    static final class Server implements AutoCloseable {
        final ConfigurableApplicationContext context;
        final String base;
        Server() { this(true); }
        Server(boolean local) {
            this(local, new Class<?>[0]);
        }
        Server(boolean local, Class<?>... additionalConfiguration) {
            var sources = new ArrayList<Class<?>>();
            sources.add(AuthApplication.class); sources.add(TestClockConfiguration.class);
            sources.addAll(Arrays.asList(additionalConfiguration));
            context = new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                .properties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                    "spring.main.banner-mode", "off", "logging.level.root", "ERROR",
                    "mp4.auth.local", Boolean.toString(local), "mp4.auth.jdbc-url", db.url(),
                    "mp4.auth.db-user", db.user(), "mp4.auth.db-password", db.password(),
                    "mp4.auth.rate-limit-secret", limiterSecret)).run();
            base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        }
        public void close() { context.close(); }
    }
    static final String limiterSecret = UUID.randomUUID().toString() + UUID.randomUUID();
    static final class Browser {
        final Server server;
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        Browser(Server server) { this.server = server; }
        HttpResponse<String> request(String method, String path, String body, String... headers) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(server.base + path)).timeout(Duration.ofSeconds(20));
            for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
            return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET", path, ""); }
        String csrf() throws Exception {
            var response = get("/api/auth/csrf");
            status(200, response, "CSRF bootstrap");
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            var data = json(response);
            assertEquals("X-CSRF-TOKEN", data.get("headerName"));
            assertTrue(data.get("token") instanceof String && !((String)data.get("token")).isBlank());
            return (String)data.get("token");
        }
        HttpResponse<String> login(Account account, String password, String token) throws Exception {
            return request("POST", "/api/auth/login", "username=" + encode(account.username()) + "&password=" + encode(password),
                "Content-Type", "application/x-www-form-urlencoded", "X-CSRF-TOKEN", token);
        }
        String cookie() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("MP4SESSION"))
                .findFirst().map(HttpCookie::getValue).orElseThrow(() -> new AssertionError("MP4SESSION missing"));
        }
    }
    @BeforeAll static void setup() throws Exception {
        db = ProvisioningDatabase.Config.fromEnvironment(Map.of(
            "MP4_AUTH_JDBC_URL", Objects.requireNonNull(System.getenv("MP4_AUTH_TEST_JDBC_URL")),
            "MP4_AUTH_DB_USER", Objects.requireNonNull(System.getenv("MP4_AUTH_TEST_DB_USER")),
            "MP4_AUTH_DB_PASSWORD", Objects.requireNonNull(System.getenv("MP4_AUTH_TEST_DB_PASSWORD"))));
        ProvisioningDatabase.initialize(db);
        app = new Server();
    }
    @AfterAll static void shutdown() { if (app != null) app.close(); }
    static Connection connection() throws SQLException { return DriverManager.getConnection(db.url(), db.user(), db.password()); }
    static Account account(String role) throws Exception {
        Account a = new Account(UUID.randomUUID(), "test-" + UUID.randomUUID(), UUID.randomUUID().toString(), role);
        try (var connection = connection(); var statement = connection.prepareStatement(
            "INSERT INTO app_accounts(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)")) {
            statement.setObject(1, a.id()); statement.setString(2, a.username()); statement.setString(3, "Acceptance " + role);
            statement.setString(4, "{bcrypt}" + ENCODER.encode(a.password())); statement.setString(5, role); statement.executeUpdate();
        }
        return a;
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static Map<String,Object> json(HttpResponse<String> response) {
        try { return JsonParserFactory.getJsonParser().parseMap(response.body()); }
        catch (RuntimeException invalid) { throw new AssertionError("Response JSON could not be parsed"); }
    }
    static void status(int expected, HttpResponse<String> actual, String action) {
        // Never attach response/request bodies: they may contain sensitive canaries.
        assertEquals(expected, actual.statusCode(), action + " HTTP status");
    }
    static void error(HttpResponse<String> response, int expected, String code) {
        status(expected, response, code);
        var data = json(response);
        assertEquals(Set.of("code", "message", "requestId"), data.keySet());
        assertEquals(code, data.get("code"));
        assertTrue(data.get("requestId") instanceof String && !((String)data.get("requestId")).isBlank());
    }
    static Browser authenticated(Account account) throws Exception {
        Browser browser = new Browser(app);
        status(200, browser.login(account, account.password(), browser.csrf()), "valid login");
        return browser;
    }
    static void identity(HttpResponse<String> response, Account account) {
        status(200, response, "current identity");
        var data = json(response);
        assertEquals(Set.of("id", "displayName", "role", "active"), data.keySet());
        assertEquals(account.id().toString(), data.get("id"));
        assertEquals("Acceptance " + account.role(), data.get("displayName"));
        assertEquals(account.role(), data.get("role")); assertEquals(true, data.get("active"));
        assertFalse(response.body().contains(account.password()), "password must not be exposed");
    }

    @Test void setupControlRealHttpAndPersistedAccounts() throws Exception {
        status(200, new Browser(app).get("/api/health"), "real server health");
        Account a = account("REPORTER");
        try (var c = connection(); var s = c.prepareStatement("SELECT password_hash FROM app_accounts WHERE id=?")) {
            s.setObject(1, a.id()); try (var rows = s.executeQuery()) {
                assertTrue(rows.next()); assertTrue(ENCODER.matches(a.password(), rows.getString(1).substring(8)));
            }
        }
    }
    @Test void allThreeRolesReceiveOnlyTheirSafeCurrentIdentity() throws Exception {
        for (String role : List.of("REPORTER", "DEVELOPER", "MAINTAINER")) {
            Account a = account(role); Browser browser = new Browser(app); String token = browser.csrf(); String old = browser.cookie();
            identity(browser.login(a, a.password(), token), a);
            assertFalse(old.equals(browser.cookie()), "login must rotate session identifier");
            identity(browser.get("/api/auth/me"), a);
            error(new Browser(app).request("GET", "/api/auth/me", "", "Cookie", "MP4SESSION=" + old), 401, "UNAUTHENTICATED");
        }
    }
    @Test void wrongUnknownAndInactiveCredentialsShareGenericFailure() throws Exception {
        Account active = account("REPORTER"), inactive = account("REPORTER");
        update(inactive, "active=false");
        Account unknown = new Account(UUID.randomUUID(), "unknown-" + UUID.randomUUID(), active.password(), "REPORTER");
        String message = null;
        for (Account a : List.of(active, unknown, inactive)) {
            Browser b = new Browser(app); var response = b.login(a, a == active ? UUID.randomUUID().toString() : a.password(), b.csrf());
            error(response, 401, "UNAUTHENTICATED");
            String next = (String)json(response).get("message"); if (message != null) assertEquals(message, next); message = next;
            error(b.get("/api/auth/me"), 401, "UNAUTHENTICATED");
        }
    }
    @Test void csrfIsSessionBoundAndMissingForeignAndCrossOriginTokensFail() throws Exception {
        Account a = account("REPORTER"); Browser b = new Browser(app), foreign = new Browser(app);
        String token = b.csrf(), other = foreign.csrf();
        String body = "username=" + encode(a.username()) + "&password=" + encode(a.password());
        error(b.request("POST", "/api/auth/login", body, "Content-Type", "application/x-www-form-urlencoded"), 403, "CSRF_INVALID");
        error(b.login(a, a.password(), other), 403, "CSRF_INVALID");
        status(403, b.request("POST", "/api/auth/login", body, "Content-Type", "application/x-www-form-urlencoded",
            "X-CSRF-TOKEN", token, "Origin", "https://foreign.invalid"), "cross-origin login");
        status(200, b.login(a, a.password(), token), "valid CSRF recovery");
    }
    @Test void loginRotatesCsrfAndLogoutInvalidatesCookieReplay() throws Exception {
        Account a = account("REPORTER"); Browser b = new Browser(app); String oldToken = b.csrf();
        status(200, b.login(a, a.password(), oldToken), "login"); String cookie = b.cookie();
        error(b.request("POST", "/api/auth/logout", "", "X-CSRF-TOKEN", oldToken), 403, "CSRF_INVALID");
        identity(b.get("/api/auth/me"), a);
        status(204, b.request("POST", "/api/auth/logout", "", "X-CSRF-TOKEN", b.csrf()), "logout");
        error(new Browser(app).request("GET", "/api/auth/me", "", "Cookie", "MP4SESSION=" + cookie), 401, "UNAUTHENTICATED");
    }
    @Test void sessionsAreJdbcBackedAndIdleAndAbsoluteExpiryAreServerChecked() throws Exception {
        Account a = account("REPORTER"); Browser idle = authenticated(a);
        try (var c = connection(); var s = c.createStatement(); var rows = s.executeQuery("SELECT count(*) FROM public.mp4_auth_session")) {
            assertTrue(rows.next()); assertTrue(rows.getInt(1) > 0, "authenticated session must persist in JDBC");
        }
        try (Server replica = new Server()) {
            identity(new Browser(replica).request("GET", "/api/auth/me", "", "Cookie", "MP4SESSION=" + idle.cookie()), a);
        }
        CLOCK.advance(Duration.ofMinutes(31)); error(idle.get("/api/auth/me"), 401, "UNAUTHENTICATED");
        Browser absolute = authenticated(account("MAINTAINER"));
        for (int i = 0; i < 16; i++) {
            CLOCK.advance(Duration.ofMinutes(29)); status(200, absolute.get("/api/auth/me"), "session before absolute expiry");
        }
        CLOCK.advance(Duration.ofMinutes(17)); error(absolute.get("/api/auth/me"), 401, "UNAUTHENTICATED");
    }
    static void update(Account a, String assignment) throws Exception {
        try (var c = connection(); var s = c.prepareStatement("UPDATE app_accounts SET " + assignment + " WHERE id=?")) {
            s.setObject(1, a.id()); assertEquals(1, s.executeUpdate());
        }
    }
    @Test void accountGenerationAndActiveStatusAreRefreshedOnEveryRequest() throws Exception {
        Account reset = account("REPORTER"), disabled = account("DEVELOPER");
        Browser first = authenticated(reset), second = authenticated(disabled);
        update(reset, "auth_generation=auth_generation+1"); update(disabled, "active=false");
        error(first.get("/api/auth/me"), 401, "UNAUTHENTICATED");
        error(second.get("/api/auth/me"), 403, "FORBIDDEN");
    }
    @Test void authPayloadRejectsUnknownRepeatedMalformedAndOversizedFields() throws Exception {
        Account a = account("REPORTER"); Browser b = new Browser(app); String token = b.csrf();
        String valid = "username=" + encode(a.username()) + "&password=" + encode(a.password());
        for (String body : List.of(valid + "&role=MAINTAINER", valid + "&username=other", "username=%ZZ&password=x", "username=" + "x".repeat(8200) + "&password=x")) {
            error(b.request("POST", "/api/auth/login", body, "Content-Type", "application/x-www-form-urlencoded", "X-CSRF-TOKEN", token), 400, "VALIDATION_FAILED");
        }
        status(200, b.login(a, a.password(), token), "validation failures preserve login ability");
    }
    @Test void productionSessionCookieHasRequiredSecurityAttributes() throws Exception {
        try (Server production = new Server(false)) {
            var response = new Browser(production).get("/api/auth/csrf");
            status(200, response, "production CSRF bootstrap");
            String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("MP4SESSION=")).findFirst()
                .orElseThrow(() -> new AssertionError("production MP4SESSION missing"));
            String lower = cookie.toLowerCase(Locale.ROOT);
            assertTrue(lower.matches(".*;\\s*secure(?:;.*|$)"), "session cookie must be Secure");
            assertTrue(lower.matches(".*;\\s*httponly(?:;.*|$)"), "session cookie must be HttpOnly");
            assertTrue(lower.matches(".*;\\s*samesite=lax(?:;.*|$)"), "session cookie must be SameSite=Lax");
            assertFalse(lower.matches(".*;\\s*domain=.*"), "session cookie must be host-only");
        }
    }
    @Test void loginBudgetIsSharedAcrossInstancesAndRecoversAfterWindow() throws Exception {
        Account a = account("REPORTER"); Browser b = new Browser(app); String token = b.csrf();
        for (int i = 0; i < 10; i++) error(b.login(a, UUID.randomUUID().toString(), token), 401, "UNAUTHENTICATED");
        try (Server replica = new Server()) {
            Browser other = new Browser(replica); var limited = other.login(a, a.password(), other.csrf());
            error(limited, 429, "AUTH_RATE_LIMITED");
            int retry = Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow());
            assertTrue(retry > 0 && retry <= 900, "bounded retry interval");
            CLOCK.advance(Duration.ofMinutes(16)); status(200, other.login(a, a.password(), other.csrf()), "budget window recovery");
        }
    }
    @Test void realDeveloperReadPreservesAssignmentsAndRejectsForgedIdentity() throws Exception {
        Account developer = account("DEVELOPER"), other = account("DEVELOPER"), reporter = account("REPORTER"), maintainer = account("MAINTAINER");
        UUID ownReport = UUID.randomUUID(), otherReport = UUID.randomUUID();
        try (var c = connection(); var s = c.prepareStatement("INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
            for (var pair : List.of(Map.entry(ownReport, developer.id()), Map.entry(otherReport, other.id()))) {
                s.setObject(1, pair.getKey()); s.setString(2, "Acceptance report"); s.setString(3, "Fixture description");
                s.setObject(4, reporter.id()); s.setObject(5, pair.getValue()); s.executeUpdate();
            }
        }
        Browser b = authenticated(developer);
        var response = b.request("GET", "/api/developer/reports", "", "X-User-Id", other.id().toString(), "X-Role", "MAINTAINER");
        status(200, response, "real assigned-list adapter");
        assertTrue(response.body().contains(ownReport.toString()), "own assignment must be visible");
        assertFalse(response.body().contains(otherReport.toString()), "foreign assignment must stay hidden");
        for (Account a : List.of(reporter, maintainer)) error(authenticated(a).get("/api/developer/reports"), 403, "FORBIDDEN");
        error(new Browser(app).request("GET", "/api/developer/reports", "", "Authorization", "Bearer " + UUID.randomUUID(),
            "X-User-Id", developer.id().toString(), "X-Role", "DEVELOPER"), 401, "UNAUTHENTICATED");
        error(new Browser(app).get("/api/auth/me?session=" + encode(b.cookie())), 401, "UNAUTHENTICATED");
    }
}
