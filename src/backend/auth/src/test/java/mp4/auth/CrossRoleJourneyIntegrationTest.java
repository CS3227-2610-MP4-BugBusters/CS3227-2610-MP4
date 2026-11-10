package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises one report journey through authenticated role-specific APIs and PostgreSQL. */
class CrossRoleJourneyIntegrationTest {
    @BeforeAll static void start() throws Exception { SessionAuthenticationTest.setup(); }
    @AfterAll static void stop() { SessionAuthenticationTest.shutdown(); }

    @Test void assignmentThroughReturnPreservesSharedHistoryAndKeepsNotesPrivate() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,status,version) VALUES(?,?,?,?,'SUBMITTED',0)")) {
            insert.setObject(1, report); insert.setString(2, "Cross-role journey");
            insert.setString(3, "Integration fixture"); insert.setObject(4, reporter.id()); insert.executeUpdate();
        }
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        var developerBrowser = SessionAuthenticationTest.authenticated(developer);
        String maintainerPath = "/api/maintainer/reports/" + report;
        String developerPath = "/api/developer/reports/" + report;
        String checklistPath = "/api/reports/" + report + "/checklist";

        SessionAuthenticationTest.status(200, write(maintainerBrowser, "POST", maintainerPath + "/assignment",
                "{\"expectedVersion\":0,\"developerId\":\"" + developer.id() + "\"}"), "assignment");
        SessionAuthenticationTest.status(200, write(developerBrowser, "POST", developerPath + "/start",
                "{\"expectedVersion\":1}"), "start investigation");
        SessionAuthenticationTest.status(200, write(developerBrowser, "PUT", developerPath + "/notes",
                "{\"expectedRevision\":0,\"text\":\"Private journey marker\"}"), "private notes");
        SessionAuthenticationTest.status(200, write(developerBrowser, "PUT", checklistPath + "/draft",
                "{\"expectedDraftRevision\":0,\"steps\":[\"Inspect the trace\"]}"), "save checklist");
        SessionAuthenticationTest.status(200, write(developerBrowser, "POST", checklistPath + "/share",
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":2}"), "share checklist");
        SessionAuthenticationTest.status(200, write(developerBrowser, "POST", developerPath + "/resolution",
                "{\"expectedVersion\":3,\"resolution\":\"Service restored\"}"), "propose resolution");

        UUID proposal;
        long proposalVersion;
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT p.id,p.version FROM reports r JOIN resolution_proposals p ON p.id=r.current_proposal_id WHERE r.id=?")) {
            query.setObject(1, report);
            try (var row = query.executeQuery()) {
                assertTrue(row.next()); proposal = row.getObject(1, UUID.class); proposalVersion = row.getLong(2);
            }
        }
        SessionAuthenticationTest.status(200, write(maintainerBrowser, "POST", maintainerPath + "/resolution/return",
                "{\"expectedVersion\":4,\"proposalId\":\"" + proposal + "\",\"proposalVersion\":"
                        + proposalVersion + ",\"reason\":\"Need another trace\"}"), "return proposal");

        var detail = maintainerBrowser.get(maintainerPath);
        SessionAuthenticationTest.status(200, detail, "maintainer detail after return");
        var body = SessionAuthenticationTest.json(detail);
        assertEquals("In progress", body.get("status"));
        assertTrue(detail.body().contains("Inspect the trace"));
        assertFalse(detail.body().contains("Private journey marker"));
        assertFalse(detail.body().contains("privateNotes"));
        var history = (List<?>) body.get("history");
        assertTrue(history.stream().map(event -> (Map<?, ?>) event).anyMatch(event ->
                ((String) event.get("text")).contains("Service restored")));
        assertTrue(history.stream().map(event -> (Map<?, ?>) event).anyMatch(event ->
                ((String) event.get("text")).contains("Need another trace")));
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT r.version,p.status,c.shared_steps FROM reports r JOIN resolution_proposals p ON p.id=? "
                        + "JOIN investigation_checklists c ON c.report_id=r.id WHERE r.id=?")) {
            query.setObject(1, proposal); query.setObject(2, report);
            try (var row = query.executeQuery()) {
                assertTrue(row.next()); assertEquals(5, row.getLong(1)); assertEquals("SUPERSEDED", row.getString(2));
                assertArrayEquals(new String[]{"Inspect the trace"}, (String[]) row.getArray(3).getArray());
            }
        }
    }

    private static HttpResponse<String> write(SessionAuthenticationTest.Browser browser, String method,
            String path, String body) throws Exception {
        return browser.request(method, path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());
    }
}
