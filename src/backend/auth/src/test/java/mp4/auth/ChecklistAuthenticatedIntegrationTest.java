package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises checklist Share and shared reads through the real session/security chain. */
class ChecklistAuthenticatedIntegrationTest {
    @BeforeAll static void startAuthenticatedServer() throws Exception {
        SessionAuthenticationTest.setup();
    }

    @AfterAll static void stopAuthenticatedServer() {
        SessionAuthenticationTest.shutdown();
    }

    @Test void assignedDeveloperSharesAndReporterReadsOnlyPublishedCopy() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        fixture(report, reporter.id(), developer.id(), new String[] {"Compare the first trace"}, new String[0]);

        var dev = SessionAuthenticationTest.authenticated(developer);
        var share = share(dev, report, "{\"expectedDraftRevision\":0,\"expectedReportVersion\":0}", dev.csrf());
        SessionAuthenticationTest.status(200, share, "assigned Developer Share");
        assertEquals(Map.of("reportVersion", 1, "changed", true), SessionAuthenticationTest.json(share));
        assertDatabase(report, 1, new String[] {"Compare the first trace"}, new String[] {"Compare the first trace"});
        var reporterBrowser = SessionAuthenticationTest.authenticated(reporter);
        var read = reporterBrowser.get(sharedPath(report));
        SessionAuthenticationTest.status(200, read, "owning Reporter shared read");
        assertEquals(Map.of("steps", java.util.List.of("Compare the first trace")), SessionAuthenticationTest.json(read));
    }

    @Test void unrelatedDeveloperAndMissingOrInvalidCsrfCannotMutateChecklist() throws Exception {
        var assigned = SessionAuthenticationTest.account("DEVELOPER");
        var unrelated = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        fixture(report, reporter.id(), assigned.id(), new String[] {"Keep this private step"}, new String[0]);

        var browser = SessionAuthenticationTest.authenticated(unrelated);
        var body = "{\"expectedDraftRevision\":0,\"expectedReportVersion\":0}";
        var absent = share(browser, report, body, null);
        SessionAuthenticationTest.error(absent, 403, "CSRF_INVALID");
        assertDatabase(report, 0, new String[] {"Keep this private step"}, new String[0]);

        var invalid = share(browser, report, body, "not-a-session-token");
        SessionAuthenticationTest.error(invalid, 403, "CSRF_INVALID");
        assertDatabase(report, 0, new String[] {"Keep this private step"}, new String[0]);

        var allowedCsrfDeniedActor = share(browser, report, body, browser.csrf());
        SessionAuthenticationTest.error(allowedCsrfDeniedActor, 403, "FORBIDDEN");
        assertDatabase(report, 0, new String[] {"Keep this private step"}, new String[0]);
        var read = browser.get(sharedPath(report));
        SessionAuthenticationTest.error(read, 403, "FORBIDDEN");
        assertDatabase(report, 0, new String[] {"Keep this private step"}, new String[0]);
    }

    private static HttpResponse<String> share(SessionAuthenticationTest.Browser browser, UUID report,
            String body, String csrf) throws Exception {
        if (csrf == null) return browser.request("POST", "/api/reports/" + report + "/checklist/share", body,
                "Content-Type", "application/json");
        return browser.request("POST", "/api/reports/" + report + "/checklist/share", body,
                "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
    }

    private static String sharedPath(UUID report) { return "/api/reports/" + report + "/checklist/shared"; }

    private static void fixture(UUID report, UUID reporter, UUID assignee, String[] draft, String[] shared) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
            insert.setObject(1, report); insert.setString(2, "Checklist integration");
            insert.setString(3, "Disposable acceptance fixture"); insert.setObject(4, reporter);
            insert.setObject(5, assignee); insert.executeUpdate();
        }
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?::text[],0,?::text[])")) {
            insert.setObject(1, report); insert.setString(2, pgArray(draft)); insert.setString(3, pgArray(shared)); insert.executeUpdate();
        }
    }

    private static String pgArray(String[] values) {
        if (values.length == 0) return "{}";
        return "{" + String.join(",", java.util.Arrays.stream(values)
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList()) + "}";
    }

    private static void assertDatabase(UUID report, long expectedVersion, String[] draft, String[] shared) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var query = c.prepareStatement("SELECT r.version,c.draft_steps,c.shared_steps FROM reports r JOIN investigation_checklists c ON c.report_id=r.id WHERE r.id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); assertEquals(expectedVersion, rows.getLong(1));
                assertArrayEquals(draft, (String[]) rows.getArray(2).getArray());
                assertArrayEquals(shared, (String[]) rows.getArray(3).getArray());
            }
        }
    }
}
