package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.LoggerFactory;

/** Real-session, PostgreSQL integration coverage for the advisory suggestion boundary. */
class SuggestionAuthenticatedIntegrationTest {
    private static TestServer server;
    private static final FakeProvider PROVIDER = new FakeProvider();

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
                    "mp4.auth.rate-limit-secret", UUID.randomUUID().toString() + UUID.randomUUID()))
                .run();
            base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        }

        @Override public void close() { context.close(); }
    }

    static final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();

        HttpResponse<String> request(String method, String path, String body, String... headers) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(server.base + path)).timeout(Duration.ofSeconds(20));
            for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
            return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET", path, ""); }
        String csrf() throws Exception {
            var response = get("/api/auth/csrf");
            SessionAuthenticationTest.status(200, response, "CSRF bootstrap");
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
        String cookie() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("MP4SESSION"))
                .findFirst().map(HttpCookie::getValue).orElseThrow();
        }
    }

    record Account(UUID id, String username, String password) { }
    record Snapshot(String title, String description, UUID assignee, String status, long version,
            String[] draft, long draftRevision, String[] shared) { }

    static final class FakeProvider implements SuggestionProvider {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> title = new AtomicReference<>();
        final AtomicReference<String> description = new AtomicReference<>();
        final AtomicReference<List<String>> checklist = new AtomicReference<>();
        final AtomicReference<ProviderResponse> next = new AtomicReference<>(
            new ProviderResponse("[\"Compare the first trace\"]", null));
        volatile boolean failModelName;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        void reset() {
            calls.set(0); title.set(null); description.set(null); checklist.set(null);
            next.set(new ProviderResponse("[\"Compare the first trace\"]", null));
            failModelName = false;
            entered = null; release = null;
        }
        @Override public String modelName() {
            if (failModelName) throw new IllegalStateException("synthetic-model-canary");
            return null;
        }
        void block() { entered = new CountDownLatch(1); release = new CountDownLatch(1); }
        public ProviderResponse suggest(String boundedTitle, String boundedDescription, List<String> savedChecklist) {
            calls.incrementAndGet(); title.set(boundedTitle); description.set(boundedDescription);
            checklist.set(savedChecklist == null ? null : List.copyOf(savedChecklist));
            return complete();
        }
        @Override public ProviderResponse suggest(String boundedTitle, String boundedDescription) {
            calls.incrementAndGet(); title.set(boundedTitle); description.set(boundedDescription);
            return complete();
        }
        private ProviderResponse complete() {
            CountDownLatch started = entered, finish = release;
            if (started != null && finish != null) {
                started.countDown();
                try {
                    if (!finish.await(10, TimeUnit.SECONDS)) throw new AssertionError("fake provider release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError("fake provider interrupted", interrupted);
                }
            }
            return next.get();
        }
    }

    @BeforeAll static void startServer() {
        var db = ProvisioningDatabase.Config.fromEnvironment(Map.of(
            "MP4_AUTH_JDBC_URL", System.getenv("MP4_AUTH_TEST_JDBC_URL"),
            "MP4_AUTH_DB_USER", System.getenv("MP4_AUTH_TEST_DB_USER"),
            "MP4_AUTH_DB_PASSWORD", System.getenv("MP4_AUTH_TEST_DB_PASSWORD")));
        ProvisioningDatabase.initialize(db);
        server = new TestServer(db);
    }

    @AfterAll static void stopServer() { if (server != null) server.close(); }

    @Test void assignedDeveloperGetsBoundedAdvisoryStepsWithoutMutation() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        Snapshot before = snapshot(report);
        Browser browser = authenticated(developer);

        var response = suggestions(browser, report, "{}", browser.csrf());

        SessionAuthenticationTest.status(200, response, "assigned Developer suggestions");
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var json = SessionAuthenticationTest.json(response);
        assertEquals(java.util.Set.of("steps", "sourceVersion", "draftRevision"), json.keySet());
        assertEquals(java.util.List.of("Compare the first trace"), json.get("steps"));
        assertInstanceOf(Number.class, json.get("sourceVersion"));
        assertEquals(before.version(), ((Number) json.get("sourceVersion")).longValue());
        assertEquals(1, PROVIDER.calls.get());
        assertEquals("Synthetic report title", PROVIDER.title.get());
        assertEquals("Synthetic report description", PROVIDER.description.get());
        assertEquals(List.of("private draft canary"), PROVIDER.checklist.get());
        assertEquals(4, ((Number) json.get("draftRevision")).longValue());
        assertSnapshotEquals(before, snapshot(report));
    }

    @Test void savedChecklistIsPassedToProviderAndDraftRevisionIsReturned() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        replaceDraft(report, new String[] {"Check the browser console", "Inspect the login request"}, 5);
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO developer_private_notes(report_id,text) VALUES(?,?)")) {
            insert.setObject(1, report); insert.setString(2, "private-note-must-not-be-sent"); insert.executeUpdate();
        }
        Browser browser = authenticated(developer);

        var response = suggestions(browser, report, "{}", browser.csrf());

        SessionAuthenticationTest.status(200, response, "suggestions include saved checklist context");
        var json = SessionAuthenticationTest.json(response);
        assertEquals(List.of("Check the browser console", "Inspect the login request"), PROVIDER.checklist.get());
        assertEquals(5, ((Number) json.get("draftRevision")).longValue());
        assertFalse(String.join(" ", PROVIDER.checklist.get()).contains("private-note-must-not-be-sent"));
    }

    @Test void changingSavedChecklistDuringGenerationDiscardsSuggestions() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        PROVIDER.block();
        Browser browser = authenticated(developer);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var pending = CompletableFuture.supplyAsync(() -> {
                try { return suggestions(browser, report, "{}", browser.csrf()); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }, executor);
            assertTrue(PROVIDER.entered.await(5, TimeUnit.SECONDS), "provider should receive the saved checklist");
            replaceDraft(report, new String[] {"New saved investigation step"}, 5);
            PROVIDER.release.countDown();
            var response = pending.get(10, TimeUnit.SECONDS);
            SessionAuthenticationTest.error(response, 409, "STALE_SOURCE");
            assertFalse(response.body().contains("Compare the first trace"));
        } finally {
            if (PROVIDER.release != null) PROVIDER.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void oversizedSavedChecklistIsRejectedBeforeProviderInvocation() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        replaceDraft(report, new String[] {"x".repeat(8193)}, 5);
        Browser browser = authenticated(developer);

        var response = suggestions(browser, report, "{}", browser.csrf());

        SessionAuthenticationTest.error(response, 400, "INPUT_TOO_LARGE");
        assertEquals(0, PROVIDER.calls.get(), "oversized saved checklist must not reach the provider");
    }

    @Test void finalSuggestionSnapshotWaitsForConcurrentChecklistWriter() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        PROVIDER.block();
        Browser browser = authenticated(developer);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ReportRowLock writer = null;
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            pending = CompletableFuture.supplyAsync(() -> {
                try { return suggestions(browser, report, "{}", browser.csrf()); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }, executor);
            assertTrue(PROVIDER.entered.await(5, TimeUnit.SECONDS), "provider should block after source capture");

            writer = lockAndUpdateChecklist(report);
            PROVIDER.release.countDown();

            awaitSuggestionSnapshotBlockedBy(writer.backendPid);
            writer.commit();
            var response = pending.get(10, TimeUnit.SECONDS);
            SessionAuthenticationTest.error(response, 409, "STALE_SOURCE");
            assertFalse(response.body().contains("Compare the first trace"));
        } finally {
            if (PROVIDER.release != null) PROVIDER.release.countDown();
            if (writer != null) writer.close();
            if (pending != null && !pending.isDone()) pending.cancel(true);
            executor.shutdownNow();
        }
    }

    @Test void generatedLineBreaksAreRejectedWithoutLeakingOrMutating() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        Snapshot before = snapshot(report);
        Browser browser = authenticated(developer);
        PROVIDER.next.set(new SuggestionProvider.ProviderResponse(
            "[\"line-break-canary\\r\\nsecond line\"]", null));

        var response = suggestions(browser, report, "{}", browser.csrf());
        Snapshot after = snapshot(report);

        assertAll(
            () -> SessionAuthenticationTest.error(response, 502, "AI_INVALID_OUTPUT"),
            () -> assertFalse(response.body().contains("line-break-canary"), "raw provider output must not be returned"),
            () -> assertEquals(1, PROVIDER.calls.get()),
            () -> assertSnapshotEquals(before, after));
    }

    @Test void jsonpContentTypeIsRejectedBeforeProviderInvocation() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        Snapshot before = snapshot(report);
        Browser browser = authenticated(developer);
        String csrf = browser.csrf();

        var response = browser.request("POST", "/api/reports/" + report + "/checklist/suggestions", "{}",
            "Content-Type", "application/jsonp", "X-CSRF-TOKEN", csrf);
        Snapshot after = snapshot(report);

        assertAll(
            () -> SessionAuthenticationTest.error(response, 400, "VALIDATION_FAILED"),
            () -> assertEquals(0, PROVIDER.calls.get(), "invalid media type must be rejected before provider use"),
            () -> assertSnapshotEquals(before, after));
    }

    @Test void telemetryRecordsUsageOrUnavailableWithoutSensitiveContext() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        Snapshot before = snapshot(report);
        Browser browser = authenticated(developer);
        Logger logger = (Logger) LoggerFactory.getLogger("mp4.platform.SuggestionService");
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            PROVIDER.next.set(new SuggestionProvider.ProviderResponse(
                "[\"provider-output-canary\"]", new SuggestionProvider.Usage(17L, 5L)));
            var withUsage = suggestions(browser, report, "{}", browser.csrf());
            SessionAuthenticationTest.status(200, withUsage, "suggestions with provider usage");

            PROVIDER.next.set(new SuggestionProvider.ProviderResponse(
                "[\"second-provider-output-canary\"]", null));
            var withoutUsage = suggestions(browser, report, "{}", browser.csrf());
            SessionAuthenticationTest.status(200, withoutUsage, "suggestions without provider usage");

            List<String> events = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("event=developer_ai_suggestion")).toList();
            String usageEvent = events.stream().filter(message -> message.contains("input_tokens=17")
                    && message.contains("output_tokens=5")).findFirst().orElse("");
            String unavailableEvent = events.stream().filter(message -> message.contains("input_tokens=unavailable")
                    && message.contains("output_tokens=unavailable")).findFirst().orElse("");

            assertAll(
                () -> assertEquals(2, events.size(), "one sanitized telemetry event per AI request"),
                () -> assertTelemetryEvent(usageEvent),
                () -> assertTelemetryEvent(unavailableEvent),
                () -> assertFalse(events.toString().contains("Synthetic report title")),
                () -> assertFalse(events.toString().contains("Synthetic report description")),
                () -> assertFalse(events.toString().contains("private draft canary")),
                () -> assertFalse(events.toString().contains("provider-output-canary")),
                () -> assertFalse(events.toString().contains("second-provider-output-canary")),
                () -> assertSnapshotEquals(before, snapshot(report)));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test void modelTelemetryFailureDoesNotChangeSuccessfulResponse() throws Exception {
        PROVIDER.reset();
        PROVIDER.failModelName = true;
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        Snapshot before = snapshot(report);
        Browser browser = authenticated(developer);
        Logger logger = (Logger) LoggerFactory.getLogger("mp4.platform.SuggestionService");
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        ListAppender<ILoggingEvent> rootAppender = new ListAppender<>();
        appender.start();
        rootAppender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        rootLogger.addAppender(rootAppender);
        try {
            var response = suggestions(browser, report, "{}", browser.csrf());
            Snapshot after = snapshot(report);
            String rootLogs = rootAppender.list.stream().map(event -> event.getFormattedMessage() + " "
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage())).toList().toString();

            assertAll(
                () -> SessionAuthenticationTest.status(200, response, "telemetry failure must not fail suggestions"),
                () -> assertEquals(java.util.List.of("Compare the first trace"),
                        SessionAuthenticationTest.json(response).get("steps")),
                () -> assertEquals(1, PROVIDER.calls.get()),
                () -> assertSnapshotEquals(before, after),
                () -> assertFalse(appender.list.toString().contains("synthetic-model-canary")),
                () -> assertFalse(rootLogs.contains("synthetic-model-canary"), "telemetry exception must not enter server logs"));
        } finally {
            logger.detachAppender(appender);
            rootLogger.detachAppender(rootAppender);
            logger.setLevel(previousLevel);
            appender.stop();
            rootAppender.stop();
        }
    }

    private static void assertTelemetryEvent(String event) {
        assertFalse(event.isBlank(), "sanitized telemetry event must be present");
        assertTrue(event.contains("outcome=success"));
        assertTrue(event.contains("model=unavailable"), "the fake provider exposes no model identifier");
        assertTrue(Pattern.compile("(?:^| )duration_ms=\\d+(?: |$)").matcher(event).find(), event);
        assertTrue(event.contains("timeout_ms=15000"));
        assertTrue(event.contains("in_flight_limit=1"));
    }

    @Test void unassignedDeveloperDoesNotInvokeProvider() throws Exception {
        PROVIDER.reset();
        var assigned = account("DEVELOPER"); var unrelated = account("DEVELOPER");
        var reporter = account("REPORTER"); UUID report = UUID.randomUUID();
        fixture(report, reporter.id(), assigned.id()); Snapshot before = snapshot(report);

        Browser unrelatedBrowser = authenticated(unrelated);
        var unassigned = suggestions(unrelatedBrowser, report, "{}", unrelatedBrowser.csrf());
        SessionAuthenticationTest.error(unassigned, 403, "FORBIDDEN");
        assertEquals(0, PROVIDER.calls.get());
        assertSnapshotEquals(before, snapshot(report));
    }

    @Test void wrongRoleDoesNotInvokeProvider() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id()); Snapshot before = snapshot(report);

        Browser reporterBrowser = authenticated(reporter);
        var wrongRole = suggestions(reporterBrowser, report, "{}", reporterBrowser.csrf());
        SessionAuthenticationTest.error(wrongRole, 403, "FORBIDDEN");
        assertEquals(0, PROVIDER.calls.get());
        assertSnapshotEquals(before, snapshot(report));
    }

    @Test void unauthenticatedRequestDoesNotInvokeProvider() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id()); Snapshot before = snapshot(report);

        Browser anonymous = new Browser();
        var response = suggestions(anonymous, report, "{}", anonymous.csrf());
        SessionAuthenticationTest.error(response, 401, "UNAUTHENTICATED");
        assertEquals(0, PROVIDER.calls.get());
        assertSnapshotEquals(before, snapshot(report));
    }

    @Test void inactiveDeveloperDoesNotInvokeProvider() throws Exception {
        PROVIDER.reset();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id()); Snapshot before = snapshot(report);
        Browser browser = authenticated(developer);
        try (var c = connection(); var update = c.prepareStatement("UPDATE app_accounts SET active=false WHERE id=?")) {
            update.setObject(1, developer.id()); assertEquals(1, update.executeUpdate());
        }

        var response = suggestions(browser, report, "{}", browser.csrf());
        SessionAuthenticationTest.error(response, 403, "FORBIDDEN");
        assertEquals(0, PROVIDER.calls.get());
        assertSnapshotEquals(before, snapshot(report));
    }

    @Test void loggedOutSessionCannotReceiveBlockedProviderResult() throws Exception {
        PROVIDER.reset(); PROVIDER.block();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        Snapshot before = snapshot(report); Browser browser = authenticated(developer);

        var pending = asyncSuggestions(browser, report, "{}");
        assertTrue(PROVIDER.entered.await(2, TimeUnit.SECONDS), "provider was invoked for authorized request");
        SessionAuthenticationTest.status(204, browser.logout(), "logout while provider is blocked");
        Snapshot afterLogout = snapshot(report);
        PROVIDER.release.countDown();
        var response = pending.get(15, TimeUnit.SECONDS);

        assertRejectedWithoutSteps(response);
        assertSnapshotEquals(before, afterLogout);
        assertSnapshotEquals(afterLogout, snapshot(report));
    }

    @Test void reassignmentWhileProviderIsBlockedDiscardsResult() throws Exception {
        staleSourceCase((report, former, replacement) -> updateReport(report,
            "assignee_id=?", replacement.id()));
    }

    @Test void contentAndVersionChangeWhileProviderIsBlockedDiscardsResult() throws Exception {
        staleSourceCase((report, former, replacement) -> updateReport(report,
            "title=?,version=version+1", "Changed report title"));
    }

    @Test void contentChangeWithoutVersionBumpWhileProviderIsBlockedDiscardsResult() throws Exception {
        staleSourceCase((report, former, replacement) -> updateReport(report, "description=?",
            "Changed report description"));
    }

    @Test void versionChangeWhileProviderIsBlockedDiscardsResult() throws Exception {
        staleSourceCase((report, former, replacement) -> updateReport(report,
            "version=version+1"));
    }

    @Test void accountGenerationChangeWhileProviderIsBlockedDiscardsResult() throws Exception {
        PROVIDER.reset(); PROVIDER.block();
        var developer = account("DEVELOPER"); var reporter = account("REPORTER");
        UUID report = UUID.randomUUID(); fixture(report, reporter.id(), developer.id());
        Snapshot before = snapshot(report); Browser browser = authenticated(developer);
        var pending = asyncSuggestions(browser, report, "{}");
        assertTrue(PROVIDER.entered.await(2, TimeUnit.SECONDS), "provider was invoked for authorized request");
        try (var c = connection(); var update = c.prepareStatement(
                "UPDATE app_accounts SET auth_generation=auth_generation+1 WHERE id=?")) {
            update.setObject(1, developer.id()); assertEquals(1, update.executeUpdate());
        }
        Snapshot afterExternalChange = snapshot(report);
        PROVIDER.release.countDown();
        var response = pending.get(15, TimeUnit.SECONDS);

        assertRejectedWithoutSteps(response);
        assertSnapshotEquals(before, afterExternalChange);
        assertSnapshotEquals(afterExternalChange, snapshot(report));
    }

    @FunctionalInterface interface MidCallMutation {
        void apply(UUID report, Account former, Account replacement) throws Exception;
    }

    private static void staleSourceCase(MidCallMutation mutation) throws Exception {
        PROVIDER.reset(); PROVIDER.block();
        var developer = account("DEVELOPER"); var replacement = account("DEVELOPER");
        var reporter = account("REPORTER"); UUID report = UUID.randomUUID();
        fixture(report, reporter.id(), developer.id()); Snapshot before = snapshot(report);
        Browser browser = authenticated(developer);
        var pending = asyncSuggestions(browser, report, "{}");
        assertTrue(PROVIDER.entered.await(5, TimeUnit.SECONDS), "provider was invoked for authorized request");
        mutation.apply(report, developer, replacement);
        Snapshot afterExternalChange = snapshot(report);
        PROVIDER.release.countDown();
        var response = pending.get(15, TimeUnit.SECONDS);

        assertRejectedWithoutSteps(response);
        Snapshot after = snapshot(report);
        assertArrayEquals(before.draft(), after.draft());
        assertEquals(before.draftRevision(), after.draftRevision());
        assertArrayEquals(before.shared(), after.shared());
        assertEquals(1, PROVIDER.calls.get(), "exactly one provider invocation");
        assertSnapshotEquals(afterExternalChange, snapshot(report));
    }

    private static CompletableFuture<HttpResponse<String>> asyncSuggestions(Browser browser, UUID report, String body) {
        String csrf;
        try { csrf = browser.csrf(); } catch (Exception e) { throw new AssertionError(e); }
        return CompletableFuture.supplyAsync(() -> {
            try { return suggestions(browser, report, body, csrf); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static void assertRejectedWithoutSteps(HttpResponse<String> response) {
        assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
            "late result should be rejected with a client error");
        assertFalse(response.body().contains("Compare the first trace"));
    }

    private static void assertSnapshotEquals(Snapshot expected, Snapshot actual) {
        assertEquals(expected.title(), actual.title());
        assertEquals(expected.description(), actual.description());
        assertEquals(expected.assignee(), actual.assignee());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.version(), actual.version());
        assertArrayEquals(expected.draft(), actual.draft());
        assertEquals(expected.draftRevision(), actual.draftRevision());
        assertArrayEquals(expected.shared(), actual.shared());
    }

    private static HttpResponse<String> suggestions(Browser browser, UUID report, String body, String csrf) throws Exception {
        return browser.request("POST", "/api/reports/" + report + "/checklist/suggestions", body,
                "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
    }

    private static Browser authenticated(Account account) throws Exception {
        Browser browser = new Browser();
        SessionAuthenticationTest.status(200, browser.login(account), "valid login");
        return browser;
    }

    private static Account account(String role) throws Exception {
        Account account = new Account(UUID.randomUUID(), "suggest-" + UUID.randomUUID(), UUID.randomUUID().toString());
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO app_accounts(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)")) {
            insert.setObject(1, account.id()); insert.setString(2, account.username());
            insert.setString(3, "Suggestion " + role);
            insert.setString(4, "{bcrypt}" + SessionAuthenticationTest.ENCODER.encode(account.password()));
            insert.setString(5, role); insert.executeUpdate();
        }
        return account;
    }

    private static void fixture(UUID report, UUID reporter, UUID assignee) throws Exception {
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
            insert.setObject(1, report); insert.setString(2, "Synthetic report title");
            insert.setString(3, "Synthetic report description"); insert.setObject(4, reporter);
            insert.setObject(5, assignee); insert.executeUpdate();
        }
        try (var c = connection(); var insert = c.prepareStatement(
                "INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?::text[],4,?::text[])")) {
            insert.setObject(1, report); insert.setString(2, "{\"private draft canary\"}");
            insert.setString(3, "{\"published canary\"}"); insert.executeUpdate();
        }
    }

    private static void replaceDraft(UUID report, String[] steps, long revision) throws Exception {
        try (var c = connection(); var update = c.prepareStatement(
                "UPDATE investigation_checklists SET draft_steps=?::text[],draft_revision=? WHERE report_id=?")) {
            update.setString(1, "{" + String.join(",", java.util.Arrays.stream(steps)
                    .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .toList()) + "}");
            update.setLong(2, revision); update.setObject(3, report); assertEquals(1, update.executeUpdate());
        }
    }

    private static ReportRowLock lockAndUpdateChecklist(UUID report) throws Exception {
        var connection = connection();
        connection.setAutoCommit(false);
        try {
            int backendPid;
            try (var pid = connection.prepareStatement("SELECT pg_backend_pid()")) {
                try (var row = pid.executeQuery()) { assertTrue(row.next()); backendPid = row.getInt(1); }
            }
            try (var lock = connection.prepareStatement("SELECT id FROM reports WHERE id=? FOR UPDATE")) {
                lock.setObject(1, report);
                try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
            }
            try (var update = connection.prepareStatement("""
                    UPDATE investigation_checklists
                    SET draft_steps=ARRAY['Concurrent saved step'], draft_revision=draft_revision+1
                    WHERE report_id=?
                    """)) {
                update.setObject(1, report);
                assertEquals(1, update.executeUpdate());
            }
            return new ReportRowLock(connection, backendPid);
        } catch (Exception failure) {
            connection.rollback();
            connection.close();
            throw failure;
        }
    }

    private static void awaitSuggestionSnapshotBlockedBy(int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (var c = connection(); var query = c.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity activity
                        WHERE activity.datname = current_database()
                          AND activity.wait_event_type = 'Lock'
                          AND activity.query ILIKE '%FROM public.reports%'
                          AND ? = ANY(pg_blocking_pids(activity.pid))
                    )
                    """)) {
                query.setInt(1, blockerPid);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    if (rows.getBoolean(1)) return;
                }
            }
            Thread.sleep(20);
        }
        fail("final suggestion snapshot did not wait on the concurrent report-row writer");
    }

    private static final class ReportRowLock implements AutoCloseable {
        private final java.sql.Connection connection;
        private final int backendPid;
        private boolean finished;

        ReportRowLock(java.sql.Connection connection, int backendPid) {
            this.connection = connection;
            this.backendPid = backendPid;
        }

        void commit() throws Exception {
            connection.commit();
            finished = true;
        }

        @Override public void close() throws Exception {
            if (!finished) connection.rollback();
            connection.close();
        }
    }

    private static Snapshot snapshot(UUID report) throws Exception {
        try (var c = connection(); var q = c.prepareStatement(
                "SELECT r.title,r.description,r.assignee_id,r.status,r.version,c.draft_steps,c.draft_revision,c.shared_steps "
                + "FROM reports r JOIN investigation_checklists c ON c.report_id=r.id WHERE r.id=?")) {
            q.setObject(1, report);
            try (var rows = q.executeQuery()) {
                assertTrue(rows.next());
                return new Snapshot(rows.getString(1), rows.getString(2), rows.getObject(3, UUID.class),
                    rows.getString(4), rows.getLong(5), (String[]) rows.getArray(6).getArray(),
                    rows.getLong(7), (String[]) rows.getArray(8).getArray());
            }
        }
    }

    private static void updateReport(UUID report, String assignments, Object... values) throws Exception {
        try (var c = connection(); var update = c.prepareStatement("UPDATE reports SET " + assignments + " WHERE id=?")) {
            for (int i = 0; i < values.length; i++) update.setObject(i + 1, values[i]);
            update.setObject(values.length + 1, report); assertEquals(1, update.executeUpdate());
        }
    }

    private static java.sql.Connection connection() throws Exception {
        var config = ProvisioningDatabase.Config.fromEnvironment(Map.of(
            "MP4_AUTH_JDBC_URL", System.getenv("MP4_AUTH_TEST_JDBC_URL"),
            "MP4_AUTH_DB_USER", System.getenv("MP4_AUTH_TEST_DB_USER"),
            "MP4_AUTH_DB_PASSWORD", System.getenv("MP4_AUTH_TEST_DB_PASSWORD")));
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
