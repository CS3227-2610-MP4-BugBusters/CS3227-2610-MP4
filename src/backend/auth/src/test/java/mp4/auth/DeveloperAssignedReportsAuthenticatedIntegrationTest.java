package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real-session and persisted-database coverage for the Developer assigned-report list. */
class DeveloperAssignedReportsAuthenticatedIntegrationTest {
    @BeforeAll static void setup() throws Exception { SessionAuthenticationTest.setup(); }
    @AfterAll static void shutdown() { SessionAuthenticationTest.shutdown(); }

    @Test void currentAssignmentsSearchFiltersPaginationAndReassignmentUseTheAuthenticatedDeveloper()
            throws Exception {
        var first = SessionAuthenticationTest.account("DEVELOPER");
        var second = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID alpha = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID beta = UUID.fromString("00000000-0000-0000-0000-000000000302");
        UUID foreign = UUID.fromString("00000000-0000-0000-0000-000000000303");
        insert(alpha, reporter.id(), first.id(), "Login fails after reset", "ASSIGNED");
        insert(beta, reporter.id(), first.id(), "Login succeeds", "IN_PROGRESS");
        insert(foreign, reporter.id(), second.id(), "Login fails elsewhere", "ASSIGNED");

        var browser = SessionAuthenticationTest.authenticated(first);
        var all = browser.get("/api/developer/reports?pageSize=1&page=1");
        SessionAuthenticationTest.status(200, all, "first assigned page");
        assertPage(all, List.of(alpha), 1, 1, 2);

        var matching = browser.get("/api/developer/reports?search=LOGIN+FAILS&status=ASSIGNED");
        SessionAuthenticationTest.status(200, matching, "combined search and status");
        assertPage(matching, List.of(alpha), 1, 20, 1);
        assertFalse(matching.body().contains("Fixture description"));

        var idSearch = browser.get("/api/developer/reports?search=" + alpha);
        SessionAuthenticationTest.status(200, idSearch, "complete ID search");
        assertPage(idSearch, List.of(alpha), 1, 20, 1);

        var secondPage = browser.get("/api/developer/reports?page=2&pageSize=1");
        SessionAuthenticationTest.status(200, secondPage, "second assigned page");
        assertPage(secondPage, List.of(beta), 2, 1, 2);

        try (Connection c = SessionAuthenticationTest.connection();
             var update = c.prepareStatement("UPDATE reports SET assignee_id=? WHERE id=?")) {
            update.setObject(1, second.id()); update.setObject(2, alpha);
            assertEquals(1, update.executeUpdate());
        }
        assertPage(browser.get("/api/developer/reports?search=" + alpha), List.of(), 1, 20, 0);
        var replacement = SessionAuthenticationTest.authenticated(second);
        assertPage(replacement.get("/api/developer/reports?search=" + alpha), List.of(alpha), 1, 20, 1);
    }

    @Test void wrongRolesAndAnonymousSessionsReceiveNoReportData() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporterBrowser = SessionAuthenticationTest.authenticated(reporter);
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        for (var browser : List.of(reporterBrowser, maintainerBrowser)) {
            var denied = browser.get("/api/developer/reports");
            SessionAuthenticationTest.error(denied, 403, "FORBIDDEN");
            assertFalse(denied.body().contains(developer.id().toString()));
        }
        var anonymous = new SessionAuthenticationTest.Browser(SessionAuthenticationTest.app)
                .get("/api/developer/reports");
        SessionAuthenticationTest.error(anonymous, 401, "UNAUTHENTICATED");
        assertFalse(anonymous.body().contains(developer.id().toString()));
    }

    @Test void detailRequiresCurrentAssignmentAndDeveloperSession() throws Exception {
        var first = SessionAuthenticationTest.account("DEVELOPER");
        var second = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        insert(report, reporter.id(), first.id(), "Detail fixture title", "IN_PROGRESS");
        String path = "/api/developer/reports/" + report;
        var firstBrowser = SessionAuthenticationTest.authenticated(first);
        var secondBrowser = SessionAuthenticationTest.authenticated(second);

        var detail = firstBrowser.get(path);
        SessionAuthenticationTest.status(200, detail, "assigned detail");
        var body = SessionAuthenticationTest.json(detail);
        assertEquals(report.toString(), body.get("id"));
        assertEquals("Detail fixture title", body.get("title"));
        assertEquals("Fixture description", body.get("description"));
        assertEquals("IN_PROGRESS", body.get("status"));
        SessionAuthenticationTest.error(secondBrowser.get(path), 404, "NOT_FOUND");
        SessionAuthenticationTest.error(SessionAuthenticationTest.authenticated(reporter).get(path), 403, "FORBIDDEN");
        SessionAuthenticationTest.error(new SessionAuthenticationTest.Browser(SessionAuthenticationTest.app).get(path),
                401, "UNAUTHENTICATED");

        try (Connection c = SessionAuthenticationTest.connection();
             var update = c.prepareStatement("UPDATE reports SET assignee_id=? WHERE id=?")) {
            update.setObject(1, second.id()); update.setObject(2, report);
            assertEquals(1, update.executeUpdate());
        }
        SessionAuthenticationTest.error(firstBrowser.get(path), 404, "NOT_FOUND");
        var reassigned = secondBrowser.get(path);
        SessionAuthenticationTest.status(200, reassigned, "reassigned detail");
        assertEquals("Detail fixture title", SessionAuthenticationTest.json(reassigned).get("title"));
    }

    @Test void detailReturnsOnlyLatestReturnReasonForCurrentInProgressAssignment() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var stranger = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        insert(report, reporter.id(), developer.id(), "Returned report", "IN_PROGRESS");
        try (var c = SessionAuthenticationTest.connection(); var event = c.prepareStatement(
                "INSERT INTO report_history(id,report_id,type,text,actor_id,report_version) VALUES(?,?,?,?,?,?)")) {
            history(event, report, reporter.id(), "other_event", "Unrelated private history canary", 1);
            history(event, report, reporter.id(), "resolution_returned", "Resolution returned: old reason", 2);
            history(event, report, reporter.id(), "resolution_returned", "Resolution returned: Inspect <script>alert(1)</script> trace", 3);
        }
        var assignedDetail = SessionAuthenticationTest.authenticated(developer)
                .get("/api/developer/reports/" + report);
        SessionAuthenticationTest.status(200, assignedDetail, "current assigned detail");
        var body = SessionAuthenticationTest.json(assignedDetail);
        assertEquals("Inspect <script>alert(1)</script> trace", body.get("returnReason"));
        assertFalse(assignedDetail.body().contains("Unrelated private history canary"));
        assertFalse(assignedDetail.body().contains("old reason"));
        SessionAuthenticationTest.error(SessionAuthenticationTest.authenticated(stranger)
                .get("/api/developer/reports/" + report), 404, "NOT_FOUND");

        try (var c = SessionAuthenticationTest.connection(); var update = c.prepareStatement(
                "UPDATE reports SET status='ASSIGNED' WHERE id=?")) {
            update.setObject(1, report); update.executeUpdate();
        }
        var assignedAgain = SessionAuthenticationTest.authenticated(developer)
                .get("/api/developer/reports/" + report);
        SessionAuthenticationTest.status(200, assignedAgain, "assigned report without applicable return reason");
        assertNull(SessionAuthenticationTest.json(assignedAgain).get("returnReason"));
    }

    private static void history(java.sql.PreparedStatement event, UUID report, UUID actor,
            String type, String text, long version) throws Exception {
        event.setObject(1, UUID.randomUUID()); event.setObject(2, report); event.setString(3, type);
        event.setString(4, text); event.setObject(5, actor); event.setLong(6, version); event.executeUpdate();
    }

    @Test void startRequiresCsrfAssignmentAndCurrentVersion() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var other = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        insert(report, reporter.id(), developer.id(), "Start fixture", "ASSIGNED");
        var browser = SessionAuthenticationTest.authenticated(developer);
        var stranger = SessionAuthenticationTest.authenticated(other);
        String path = "/api/developer/reports/" + report + "/start";
        long version = ((Number) SessionAuthenticationTest.json(browser.get("/api/developer/reports/" + report)).get("version")).longValue();
        String body = "{\"expectedVersion\":" + version + "}";
        SessionAuthenticationTest.error(browser.request("POST", path, body, "Content-Type", "application/json"), 403, "CSRF_INVALID");
        SessionAuthenticationTest.error(stranger.request("POST", path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", stranger.csrf()), 409, "CONFLICT");
        SessionAuthenticationTest.error(browser.request("POST", path, "{\"expectedVersion\":-1}", "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf()), 400, "INVALID_REQUEST");
        SessionAuthenticationTest.status(200, browser.request("POST", path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf()), "start assigned report");
        SessionAuthenticationTest.error(browser.request("POST", path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf()), 409, "CONFLICT");
        var updated = SessionAuthenticationTest.json(browser.get("/api/developer/reports/" + report));
        assertEquals("IN_PROGRESS", updated.get("status"));
        assertEquals(version + 1, ((Number) updated.get("version")).longValue());
    }

    private static void insert(UUID report, UUID reporter, UUID assignee, String title, String status)
            throws Exception {
        try (Connection c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement(
                     "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,?)")) {
            insert.setObject(1, report); insert.setString(2, title); insert.setString(3, "Fixture description");
            insert.setObject(4, reporter); insert.setObject(5, assignee); insert.setString(6, status);
            insert.executeUpdate();
        }
    }

    private static void assertPage(java.net.http.HttpResponse<String> response, List<UUID> ids,
            int page, int pageSize, int total) {
        Map<String, Object> body = SessionAuthenticationTest.json(response);
        assertEquals(page, ((Number) body.get("page")).intValue());
        assertEquals(pageSize, ((Number) body.get("pageSize")).intValue());
        assertEquals(total, ((Number) body.get("totalItems")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertEquals(ids.stream().map(UUID::toString).toList(),
                items.stream().map(item -> item.get("id")).toList());
        for (var item : items) assertEquals(java.util.Set.of("id", "title", "status"), item.keySet());
    }
}
