package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Exercises private notes through real authenticated HTTP sessions and PostgreSQL. */
class DeveloperPrivateNotesIntegrationTest {
    @BeforeAll static void startAuthenticatedServer() throws Exception {
        SessionAuthenticationTest.setup();
        try (var c = SessionAuthenticationTest.connection(); var statement = c.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS developer_private_notes ("
                    + "report_id uuid PRIMARY KEY REFERENCES reports(id), text text NOT NULL, "
                    + "revision bigint NOT NULL CHECK (revision >= 0))");
        }
    }

    @AfterAll static void stopAuthenticatedServer() {
        SessionAuthenticationTest.shutdown();
    }

    @Test void assignedDeveloperReadsSavesRejectsStaleWriteAndClearsWithoutChangingReportOrProposal()
            throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        var proposal = UUID.randomUUID();
        report(report, reporter.id(), developer.id(), proposal);
        proposal(proposal, report);

        var browser = SessionAuthenticationTest.authenticated(developer);
        var initial = browser.get(path(report));
        SessionAuthenticationTest.status(200, initial, "assigned Developer initial notes read");
        noStore(initial);
        assertEquals(Map.of("text", "", "revision", 0), SessionAuthenticationTest.json(initial));

        String notes = "Private investigation marker 🧭";
        var saved = put(browser, report, notes, 0, browser.csrf());
        SessionAuthenticationTest.status(200, saved, "notes save");
        noStore(saved);
        assertEquals(Map.of("text", notes, "revision", 1), SessionAuthenticationTest.json(saved));
        assertEquals(notes, storedNotes(report));
        assertEquals(Map.of("status", "RESOLUTION_PROPOSED", "version", 7L, "proposal", proposal.toString()),
                reportState(report));
        assertEquals("PENDING", proposalStatus(proposal));

        var stale = put(browser, report, "unsent stale value", 0, browser.csrf());
        SessionAuthenticationTest.error(stale, 409, "STALE_VERSION");
        assertFalse(stale.body().contains("unsent stale value"));
        assertEquals(notes, storedNotes(report));

        var cleared = put(browser, report, "", 1, browser.csrf());
        SessionAuthenticationTest.status(200, cleared, "notes clear");
        assertEquals(Map.of("text", "", "revision", 2), SessionAuthenticationTest.json(cleared));
        assertEquals("", storedNotes(report));
        assertEquals(2L, notesRevision(report));
        assertEquals(Map.of("status", "RESOLUTION_PROPOSED", "version", 7L, "proposal", proposal.toString()),
                reportState(report));
        assertEquals("PENDING", proposalStatus(proposal));
    }

    @Test void onlyCurrentAssignedDeveloperCanReadAndClosedReportsAreReadOnly() throws Exception {
        var first = SessionAuthenticationTest.account("DEVELOPER");
        var second = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var report = UUID.randomUUID();
        report(report, reporter.id(), first.id(), null);
        saveFixtureNotes(report, "Do not disclose this note", 4);

        var firstBrowser = SessionAuthenticationTest.authenticated(first);
        var secondBrowser = SessionAuthenticationTest.authenticated(second);
        var reporterBrowser = SessionAuthenticationTest.authenticated(reporter);
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        requireNotesRoute(firstBrowser, report);
        denyWithoutNotes(secondBrowser.get(path(report)));
        denyWithoutNotes(reporterBrowser.get(path(report)));
        denyWithoutNotes(maintainerBrowser.get(path(report)));

        var assigned = firstBrowser.get(path(report));
        SessionAuthenticationTest.status(200, assigned, "current assignee can read notes");
        assertEquals(Map.of("text", "Do not disclose this note", "revision", 4),
                SessionAuthenticationTest.json(assigned));

        try (var c = SessionAuthenticationTest.connection();
             var update = c.prepareStatement("UPDATE reports SET assignee_id=? WHERE id=?")) {
            update.setObject(1, second.id()); update.setObject(2, report); assertEquals(1, update.executeUpdate());
        }
        denyWithoutNotes(firstBrowser.get(path(report)));
        var reassigned = secondBrowser.get(path(report));
        SessionAuthenticationTest.status(200, reassigned, "new assignee inherits notes");
        assertEquals(Map.of("text", "Do not disclose this note", "revision", 4),
                SessionAuthenticationTest.json(reassigned));

        try (var c = SessionAuthenticationTest.connection(); var update = c.prepareStatement(
                "UPDATE reports SET status='CLOSED' WHERE id=?")) {
            update.setObject(1, report); assertEquals(1, update.executeUpdate());
        }
        var closedRead = secondBrowser.get(path(report));
        SessionAuthenticationTest.status(200, closedRead, "assigned Developer can read closed notes");
        assertEquals(Map.of("text", "Do not disclose this note", "revision", 4),
                SessionAuthenticationTest.json(closedRead));
        SessionAuthenticationTest.error(put(secondBrowser, report, "forbidden", 4, secondBrowser.csrf()),
                409, "INVALID_STATE");
        assertEquals("Do not disclose this note", storedNotes(report));
    }

    @Test void notesRequireAuthenticationAndCsrfAndRejectInvalidText() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        report(report, reporter.id(), developer.id(), null);
        var anonymous = new SessionAuthenticationTest.Browser(SessionAuthenticationTest.app);
        SessionAuthenticationTest.error(anonymous.get(path(report)), 401, "UNAUTHENTICATED");

        var browser = SessionAuthenticationTest.authenticated(developer);
        requireNotesRoute(browser, report);
        SessionAuthenticationTest.error(put(browser, report, "must not save", 0, null), 403, "CSRF_INVALID");
        assertEquals("", storedNotes(report));
        SessionAuthenticationTest.error(put(browser, report, "x\u0000y", 0, browser.csrf()),
                400, "VALIDATION_FAILED");
        assertEquals("", storedNotes(report));
    }

    private static HttpResponse<String> put(SessionAuthenticationTest.Browser browser, UUID report,
            String text, long revision, String csrf) throws Exception {
        String body = "{\"text\":\"" + escapeJson(text) + "\",\"expectedRevision\":" + revision + "}";
        if (csrf == null) return browser.request("PUT", path(report), body, "Content-Type", "application/json");
        return browser.request("PUT", path(report), body, "Content-Type", "application/json",
                "X-CSRF-TOKEN", csrf);
    }

    private static void report(UUID id, UUID reporter, UUID assignee, UUID proposal) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version,current_proposal_id) "
                        + "VALUES(?,?,?,?,?,'RESOLUTION_PROPOSED',7,?)")) {
            insert.setObject(1, id); insert.setString(2, "Private notes integration");
            insert.setString(3, "Disposable acceptance fixture"); insert.setObject(4, reporter);
            insert.setObject(5, assignee); insert.setObject(6, proposal); insert.executeUpdate();
        }
    }

    private static void proposal(UUID id, UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO resolution_proposals(id,report_id,status,version,contents) VALUES(?,?,'PENDING',3,'Proposal fixture')")) {
            insert.setObject(1, id); insert.setObject(2, report); insert.executeUpdate();
        }
    }

    private static void saveFixtureNotes(UUID report, String text, long revision) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO developer_private_notes(report_id,text,revision) VALUES(?,?,?)")) {
            insert.setObject(1, report); insert.setString(2, text); insert.setLong(3, revision); insert.executeUpdate();
        }
    }

    private static String storedNotes(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT text FROM developer_private_notes WHERE report_id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : ""; }
        }
    }

    private static long notesRevision(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT revision FROM developer_private_notes WHERE report_id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    private static Map<String, Object> reportState(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT status,version,current_proposal_id FROM reports WHERE id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return Map.of("status", rows.getString(1), "version", rows.getLong(2),
                        "proposal", rows.getObject(3).toString());
            }
        }
    }

    private static String proposalStatus(UUID proposal) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT status FROM resolution_proposals WHERE id=?")) {
            query.setObject(1, proposal); try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static void denyWithoutNotes(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 403 || response.statusCode() == 404,
                "unauthorized notes read must not succeed");
        assertFalse(response.body().contains("Do not disclose this note"));
    }

    private static void requireNotesRoute(SessionAuthenticationTest.Browser browser, UUID report) throws Exception {
        var response = browser.get(path(report));
        Assumptions.assumeTrue(response.statusCode() != 404, "private notes route is not present yet");
    }

    private static void noStore(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    private static String path(UUID report) { return "/api/developer/reports/" + report + "/notes"; }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
