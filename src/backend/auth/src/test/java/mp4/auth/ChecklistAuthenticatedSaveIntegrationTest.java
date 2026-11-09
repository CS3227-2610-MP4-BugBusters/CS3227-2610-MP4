package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises private checklist Save through the real session/security chain. */
class ChecklistAuthenticatedSaveIntegrationTest {
    @BeforeAll static void startAuthenticatedServer() throws Exception {
        SessionAuthenticationTest.setup();
    }

    @AfterAll static void stopAuthenticatedServer() {
        SessionAuthenticationTest.shutdown();
    }

    @Test void assignedDeveloperSavesPrivatelyAndPreservesSharedCopy() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        fixture(report, reporter.id(), developer.id(), new String[0], new String[] {"Already shared"});

        var browser = SessionAuthenticationTest.authenticated(developer);
        var response = save(browser, report, "[\"New private draft\"]", 0, browser.csrf());
        SessionAuthenticationTest.status(200, response, "assigned Developer Save");
        assertEquals(1L, ((Number) SessionAuthenticationTest.json(response).get("draftRevision")).longValue());
        assertDatabase(report, 1, new String[] {"New private draft"}, new String[] {"Already shared"});
    }

    @Test void wrongRoleAndUnassignedDeveloperCannotSave() throws Exception {
        var assigned = SessionAuthenticationTest.account("DEVELOPER");
        var unrelated = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        fixture(report, reporter.id(), assigned.id(), new String[] {"Original draft"}, new String[] {"Published steps"});
        var body = "[\"Attempted replacement\"]";

        var unrelatedBrowser = SessionAuthenticationTest.authenticated(unrelated);
        var unassigned = save(unrelatedBrowser, report, body, 0, unrelatedBrowser.csrf());
        SessionAuthenticationTest.error(unassigned, 403, "FORBIDDEN");
        assertDatabase(report, 0, new String[] {"Original draft"}, new String[] {"Published steps"});

        var reporterBrowser = SessionAuthenticationTest.authenticated(reporter);
        var wrongRole = save(reporterBrowser, report, body, 0, reporterBrowser.csrf());
        SessionAuthenticationTest.error(wrongRole, 403, "FORBIDDEN");
        assertDatabase(report, 0, new String[] {"Original draft"}, new String[] {"Published steps"});
    }

    @Test void missingAndInvalidCsrfCannotMutateDraftOrSharedCopy() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        fixture(report, reporter.id(), developer.id(), new String[] {"Original draft"}, new String[] {"Published steps"});
        var browser = SessionAuthenticationTest.authenticated(developer);
        var body = "[\"Attempted replacement\"]";

        SessionAuthenticationTest.error(save(browser, report, body, 0, null), 403, "CSRF_INVALID");
        assertDatabase(report, 0, new String[] {"Original draft"}, new String[] {"Published steps"});
        SessionAuthenticationTest.error(save(browser, report, body, 0, "not-a-session-token"), 403, "CSRF_INVALID");
        assertDatabase(report, 0, new String[] {"Original draft"}, new String[] {"Published steps"});
    }

    private static HttpResponse<String> save(SessionAuthenticationTest.Browser browser, UUID report,
            String steps, long expectedRevision, String csrf) throws Exception {
        String body = "{\"steps\":" + steps + ",\"expectedDraftRevision\":" + expectedRevision + "}";
        if (csrf == null) return browser.request("PUT", path(report), body, "Content-Type", "application/json");
        return browser.request("PUT", path(report), body, "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
    }

    private static String path(UUID report) { return "/api/reports/" + report + "/checklist/draft"; }

    private static void fixture(UUID report, UUID reporter, UUID assignee,
            String[] draft, String[] shared) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
            insert.setObject(1, report); insert.setString(2, "Checklist Save integration");
            insert.setString(3, "Disposable acceptance fixture"); insert.setObject(4, reporter);
            insert.setObject(5, assignee); insert.executeUpdate();
        }
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?::text[],0,?::text[])")) {
            insert.setObject(1, report); insert.setString(2, pgArray(draft));
            insert.setString(3, pgArray(shared)); insert.executeUpdate();
        }
    }

    private static String pgArray(String[] values) {
        if (values.length == 0) return "{}";
        return "{" + String.join(",", java.util.Arrays.stream(values)
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList()) + "}";
    }

    private static void assertDatabase(UUID report, long expectedRevision, String[] draft, String[] shared) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var query = c.prepareStatement("SELECT c.draft_revision,c.draft_steps,c.shared_steps FROM reports r JOIN investigation_checklists c ON c.report_id=r.id WHERE r.id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); assertEquals(expectedRevision, rows.getLong(1));
                assertArrayEquals(draft, (String[]) rows.getArray(2).getArray());
                assertArrayEquals(shared, (String[]) rows.getArray(3).getArray());
            }
        }
    }
}
