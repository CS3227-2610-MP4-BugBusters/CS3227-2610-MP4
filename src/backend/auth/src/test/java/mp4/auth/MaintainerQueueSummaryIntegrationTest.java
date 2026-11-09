package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import mp4.maintainer.summary.SummaryContracts.*;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The real summary route must use current sessions and the persisted selected queue. */
class MaintainerQueueSummaryIntegrationTest {
    private static ControlledGateway controlled;

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledProviders {
        @Bean @Primary SummaryGateway testSummaryGateway() { return controlled; }
        @Bean @Primary BudgetAndAdmission testSummaryBudget() {
            return (actor, model, duration) -> new AdmissionLease() {
                @Override public void renewUntilWorkerTerminates() {}
                @Override public void close() {}
            };
        }
    }

    static final class ControlledGateway implements SummaryGateway {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;
        final AtomicInteger calls = new AtomicInteger();
        volatile GatewayRequest request;
        ControlledGateway(boolean block) { release = new CountDownLatch(block ? 1 : 0); }
        @Override public ModelIdentity modelIdentity() { return new ModelIdentity("controlled-test", "fixture", "1"); }
        @Override public ProviderMode providerMode() { return ProviderMode.LIVE; }
        @Override public GatewayResponse generate(GatewayRequest input) {
            request = input; calls.incrementAndGet(); entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new SummaryFailure(FailureCode.AI_TIMEOUT);
                var reports = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input.reports().value());
                String id = reports.get(0).get("id").asText();
                return new GatewayResponse("{\"overview\":[{\"text\":\"Controlled test summary\",\"reportIds\":[\""
                        + id + "\"]}],\"blockers\":[],\"missingInformation\":[]}", modelIdentity(), providerMode());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
            } catch (java.io.IOException malformed) { throw new AssertionError(malformed); }
        }
    }
    @BeforeAll static void start() throws Exception { SessionAuthenticationTest.setup(); }
    @AfterAll static void stop() { SessionAuthenticationTest.shutdown(); }

    @Test void emptyPersistedQueueNeedsNoConfiguredProvider() throws Exception {
        var account = SessionAuthenticationTest.account("MAINTAINER");
        var unassignedDeveloper = SessionAuthenticationTest.account("DEVELOPER");
        var browser = SessionAuthenticationTest.authenticated(account);
        var response = post(browser, "{\"filters\":{\"assigneeId\":\"" + unassignedDeveloper.id()
                + "\"},\"regenerate\":false}", browser.csrf());
        SessionAuthenticationTest.status(200, response, "empty authorized queue");
        var result = SessionAuthenticationTest.json(response);
        assertEquals("none", result.get("providerMode"));
        assertEquals("MNT-SUMMARY-1", result.get("promptVersion"));
        assertEquals(true, result.get("requiresReview"));
        assertEquals(List.of(), result.get("included"));
        assertEquals(List.of(), result.get("excluded"));
        assertEquals(Map.of("overview", List.of(), "blockers", List.of(), "missingInformation", List.of()), result.get("findings"));
        assertEquals(Map.of("assigneeId", unassignedDeveloper.id().toString()), result.get("filters"));
        assertNotNull(java.time.Instant.parse((String) result.get("capturedAt")));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @Test void nonemptyQueueFailsClosedWithoutGatewayAndLeavesReportsUsable() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        try (var connection = SessionAuthenticationTest.connection(); var insert = connection.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'IN_PROGRESS')")) {
            insert.setObject(1, report); insert.setString(2, "Synthetic persisted queue report");
            insert.setString(3, "Untrusted source must not become an error message");
            insert.setObject(4, reporter.id()); insert.setObject(5, developer.id()); insert.executeUpdate();
        }
        var browser = SessionAuthenticationTest.authenticated(maintainer);
        var response = post(browser, "{\"filters\":{\"assigneeId\":\"" + developer.id()
                + "\"},\"regenerate\":false}", browser.csrf());
        SessionAuthenticationTest.error(response, 503, "AI_UNAVAILABLE");
        assertFalse(response.body().contains("Untrusted source"));
        assertFalse(response.body().contains("findings"));
        var detail = browser.get("/api/maintainer/reports/" + report);
        SessionAuthenticationTest.status(200, detail, "non-AI report remains available");
        assertEquals(0, ((Number) SessionAuthenticationTest.json(detail).get("version")).longValue());
    }

    @Test void rejectsClosedUnknownAndActorControlledRequests() throws Exception {
        var browser = SessionAuthenticationTest.authenticated(SessionAuthenticationTest.account("MAINTAINER"));
        for (String body : List.of(
                "{\"filters\":{\"status\":\"Closed\"},\"regenerate\":false}",
                "{\"filters\":{\"offset\":0},\"regenerate\":false}",
                "{\"filters\":{},\"regenerate\":\"false\"}",
                "{\"filters\":{},\"regenerate\":false,\"role\":\"MAINTAINER\"}")) {
            SessionAuthenticationTest.error(post(browser, body, browser.csrf()), 400, "VALIDATION_FAILED");
        }
    }

    @Test void sessionRoleAndCsrfProtectGeneration() throws Exception {
        String body = "{\"filters\":{},\"regenerate\":false}";
        var anonymous = new SessionAuthenticationTest.Browser(SessionAuthenticationTest.app);
        SessionAuthenticationTest.error(post(anonymous, body, "invalid"), 401, "UNAUTHENTICATED");
        var reporter = SessionAuthenticationTest.authenticated(SessionAuthenticationTest.account("REPORTER"));
        SessionAuthenticationTest.error(post(reporter, body, reporter.csrf()), 403, "FORBIDDEN");
        var maintainer = SessionAuthenticationTest.authenticated(SessionAuthenticationTest.account("MAINTAINER"));
        SessionAuthenticationTest.error(post(maintainer, body, "invalid"), 403, "CSRF_INVALID");
    }

    @Test void controlledProviderUsesSafePersistedContextAndMatchingResultReuse() throws Exception {
        controlled = new ControlledGateway(false);
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        UUID report = seedReports(developer.id(), 1).get(0);
        try (var connection = SessionAuthenticationTest.connection(); var insert = connection.prepareStatement(
                "INSERT INTO investigation_checklists(report_id,draft_steps,shared_steps) VALUES(?,?::text[],?::text[])")) {
            insert.setObject(1, report); insert.setString(2, "{private-draft-marker}");
            insert.setString(3, "{published-checklist-marker}"); insert.executeUpdate();
        }
        try (var server = new SessionAuthenticationTest.Server(true, ControlledProviders.class)) {
            var browser = authenticated(server, maintainer);
            String body = selected(developer.id());
            var first = post(browser, body, browser.csrf());
            SessionAuthenticationTest.status(200, first, "controlled provider result");
            assertEquals("soc", SessionAuthenticationTest.json(first).get("providerMode"));
            String context = controlled.request.reports().value();
            assertFalse(context.contains("private-draft-marker"));
            assertFalse(context.contains("published-checklist-marker"));
            assertFalse(context.contains(maintainer.username()));
            assertFalse(context.contains("reporterId"));
            var reused = post(browser, body, browser.csrf());
            SessionAuthenticationTest.status(200, reused, "matching authorized reuse");
            assertEquals(1, controlled.calls.get());
            assertEquals(SessionAuthenticationTest.json(first).get("capturedAt"), SessionAuthenticationTest.json(reused).get("capturedAt"));
            var anotherSession = authenticated(server, maintainer);
            SessionAuthenticationTest.status(200, post(anotherSession, body, anotherSession.csrf()), "new session cannot reuse prior cache");
            assertEquals(2, controlled.calls.get());
        }
    }

    @Test void revocationDuringProviderWorkPreventsGeneratedDelivery() throws Exception {
        assertRevokedDelivery(false);
        assertRevokedDelivery(true);
    }

    private static void assertRevokedDelivery(boolean logout) throws Exception {
        controlled = new ControlledGateway(true);
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        seedReports(developer.id(), 1);
        try (var server = new SessionAuthenticationTest.Server(true, ControlledProviders.class)) {
            var browser = authenticated(server, maintainer);
            String token = browser.csrf();
            var workers = Executors.newSingleThreadExecutor();
            try {
                var pending = workers.submit(() -> post(browser, selected(developer.id()), token));
                assertTrue(controlled.entered.await(5, TimeUnit.SECONDS));
                if (logout) {
                    SessionAuthenticationTest.status(204, browser.request("POST", "/api/auth/logout", "",
                            "X-CSRF-TOKEN", token), "concurrent logout");
                } else {
                    try (var connection = SessionAuthenticationTest.connection(); var update = connection.prepareStatement(
                            "UPDATE app_accounts SET active=false,auth_generation=auth_generation+1 WHERE id=?")) {
                        update.setQueryTimeout(2); update.setObject(1, maintainer.id());
                        assertEquals(1, update.executeUpdate(), "provider work must not hold the account eligibility lock");
                    }
                }
                controlled.release.countDown();
                var denied = pending.get(5, TimeUnit.SECONDS);
                SessionAuthenticationTest.error(denied, 403, "FORBIDDEN");
                assertFalse(denied.body().contains("Controlled test summary"));
                assertFalse(denied.body().contains("findings"));
            } finally {
                controlled.release.countDown(); workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void excludedMemberVersionChangesInvalidateRealSnapshotDelivery() throws Exception {
        controlled = new ControlledGateway(true);
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        List<UUID> reports = seedReports(developer.id(), 23);
        try (var server = new SessionAuthenticationTest.Server(true, ControlledProviders.class)) {
            var browser = authenticated(server, maintainer);
            String token = browser.csrf();
            var workers = Executors.newSingleThreadExecutor();
            try {
                var pending = workers.submit(() -> post(browser, selected(developer.id()), token));
                assertTrue(controlled.entered.await(5, TimeUnit.SECONDS));
                var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(controlled.request.reports().value());
                assertEquals(20, payload.size());
                assertFalse(controlled.request.reports().value().contains(reports.get(22).toString()));
                try (var connection = SessionAuthenticationTest.connection(); var update = connection.prepareStatement(
                        "UPDATE reports SET title='Changed excluded member',version=version+1 WHERE id=?")) {
                    update.setObject(1, reports.get(22)); assertEquals(1, update.executeUpdate());
                }
                controlled.release.countDown();
                var stale = pending.get(5, TimeUnit.SECONDS);
                SessionAuthenticationTest.error(stale, 409, "AI_STALE_SOURCE");
                assertFalse(stale.body().contains("Controlled test summary"));
            } finally {
                controlled.release.countDown(); workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static SessionAuthenticationTest.Browser authenticated(SessionAuthenticationTest.Server server,
            SessionAuthenticationTest.Account account) throws Exception {
        var browser = new SessionAuthenticationTest.Browser(server);
        SessionAuthenticationTest.status(200, browser.login(account, account.password(), browser.csrf()), "controlled server login");
        return browser;
    }

    private static String selected(UUID developer) {
        return "{\"filters\":{\"assigneeId\":\"" + developer + "\"},\"regenerate\":false}";
    }

    private static List<UUID> seedReports(UUID developer, int count) throws Exception {
        var reporter = SessionAuthenticationTest.account("REPORTER");
        List<UUID> reports = new ArrayList<>();
        try (var connection = SessionAuthenticationTest.connection(); var insert = connection.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,updated_at) VALUES(?,?,?,?,?,'IN_PROGRESS',?)")) {
            var base = java.time.Instant.now().minusSeconds(100);
            for (int index = 0; index < count; index++) {
                UUID id = UUID.randomUUID(); reports.add(id);
                insert.setObject(1, id); insert.setString(2, "Controlled queue source " + index);
                insert.setString(3, "Synthetic source text"); insert.setObject(4, reporter.id());
                insert.setObject(5, developer); insert.setTimestamp(6, java.sql.Timestamp.from(base.plusSeconds(index)));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        return reports;
    }

    private static HttpResponse<String> post(SessionAuthenticationTest.Browser browser, String body, String token) throws Exception {
        return browser.request("POST", "/api/maintainer/queue-summary", body,
                "Content-Type", "application/json", "X-CSRF-TOKEN", token);
    }
}
