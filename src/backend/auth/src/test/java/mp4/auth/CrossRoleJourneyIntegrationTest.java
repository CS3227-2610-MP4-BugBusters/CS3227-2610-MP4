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

    @Test void assignedDeveloperListsOwnAssignmentNotificationAndAcknowledgesIt() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,status,version) VALUES(?,?,?,?,'SUBMITTED',0)")) {
            insert.setObject(1, report); insert.setString(2, "Notification private report title");
            insert.setString(3, "Sensitive report body"); insert.setObject(4, reporter.id()); insert.executeUpdate();
        }
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        var developerBrowser = SessionAuthenticationTest.authenticated(developer);
        SessionAuthenticationTest.status(200, write(maintainerBrowser, "POST", "/api/maintainer/reports/" + report + "/assignment",
                "{\"expectedVersion\":0,\"developerId\":\"" + developer.id() + "\"}"), "assignment");

        var notifications = developerBrowser.get("/api/developer/notifications");
        SessionAuthenticationTest.status(200, notifications, "Developer notification list");
        Map<String, Object> item = (Map<String, Object>) ((List<?>) SessionAuthenticationTest.json(notifications).get("items")).get(0);
        UUID notification = UUID.fromString((String) item.get("id"));
        assertEquals("ASSIGNED", item.get("type"));
        assertEquals(report.toString(), item.get("reportId"));
        assertEquals(false, item.get("read"));
        assertFalse(notifications.body().contains("Notification private report title"));
        assertFalse(notifications.body().contains("Sensitive report body"));

        SessionAuthenticationTest.status(204, write(developerBrowser, "POST",
                "/api/developer/notifications/" + notification + "/read", ""), "mark owned notification read");
        SessionAuthenticationTest.status(204, write(developerBrowser, "POST",
                "/api/developer/notifications/" + notification + "/read", ""), "repeat read acknowledgement");
        var reread = developerBrowser.get("/api/developer/notifications");
        SessionAuthenticationTest.status(200, reread, "read notification list");
        Map<String, Object> readItem = (Map<String, Object>) ((List<?>) SessionAuthenticationTest.json(reread).get("items")).get(0);
        assertEquals(true, readItem.get("read"));
    }

    @Test void reassignmentHidesFormerReportIdentityAndDeniesCrossUserRead() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var former = SessionAuthenticationTest.account("DEVELOPER");
        var current = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,status,version) VALUES(?,?,?,?,'SUBMITTED',0)")) {
            insert.setObject(1, report); insert.setString(2, "Private report title");
            insert.setString(3, "Private report description"); insert.setObject(4, reporter.id()); insert.executeUpdate();
        }
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        var formerBrowser = SessionAuthenticationTest.authenticated(former);
        var currentBrowser = SessionAuthenticationTest.authenticated(current);
        SessionAuthenticationTest.status(200, write(maintainerBrowser, "POST", "/api/maintainer/reports/" + report + "/assignment",
                "{\"expectedVersion\":0,\"developerId\":\"" + former.id() + "\"}"), "initial assignment");
        SessionAuthenticationTest.status(200, write(maintainerBrowser, "POST", "/api/maintainer/reports/" + report + "/assignment",
                "{\"expectedVersion\":1,\"developerId\":\"" + current.id() + "\",\"reason\":\"Rebalance workload\"}"), "reassignment");

        var formerList = formerBrowser.get("/api/developer/notifications");
        SessionAuthenticationTest.status(200, formerList, "former Developer notifications");
        List<?> formerItems = (List<?>) SessionAuthenticationTest.json(formerList).get("items");
        assertEquals(2, formerItems.size());
        UUID anotherDevelopersNotification = null;
        for (Object raw : formerItems) {
            Map<?, ?> item = (Map<?, ?>) raw;
            assertNull(item.get("reportId"), "former assignee must not learn the report identity");
            assertFalse(formerList.body().contains("Private report title"));
            assertFalse(formerList.body().contains("Private report description"));
        }
        var currentList = currentBrowser.get("/api/developer/notifications");
        SessionAuthenticationTest.status(200, currentList, "current Developer notifications");
        Map<?, ?> currentItem = (Map<?, ?>) ((List<?>) SessionAuthenticationTest.json(currentList).get("items")).get(0);
        assertEquals("ASSIGNED", currentItem.get("type"));
        assertEquals(report.toString(), currentItem.get("reportId"));
        anotherDevelopersNotification = UUID.fromString((String) currentItem.get("id"));
        SessionAuthenticationTest.status(404, write(formerBrowser, "POST",
                "/api/developer/notifications/" + anotherDevelopersNotification + "/read", ""),
                "cross-user notification read is hidden");
        SessionAuthenticationTest.status(404, formerBrowser.get("/api/developer/reports/" + report),
                "notification does not restore former assignment access");
    }

    @Test void resolutionReturnAndReopenCreateGenericNotifications() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID returnedReport = UUID.randomUUID();
        UUID proposal = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection()) {
            try (var insert = c.prepareStatement("INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version,current_proposal_id) VALUES(?,?,?, ?,?,'RESOLUTION_PROPOSED',4,?)")) {
                insert.setObject(1, returnedReport); insert.setString(2, "Return report"); insert.setString(3, "Private text");
                insert.setObject(4, reporter.id()); insert.setObject(5, developer.id()); insert.setObject(6, proposal); insert.executeUpdate();
            }
            try (var insert = c.prepareStatement("INSERT INTO resolution_proposals(id,report_id,status,version,contents) VALUES(?,?,'PENDING',1,?)")) {
                insert.setObject(1, proposal); insert.setObject(2, returnedReport); insert.setString(3, "Synthetic resolution"); insert.executeUpdate();
            }
        }
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        var developerBrowser = SessionAuthenticationTest.authenticated(developer);
        SessionAuthenticationTest.status(200, write(maintainerBrowser, "POST", "/api/maintainer/reports/" + returnedReport + "/resolution/return",
                "{\"expectedVersion\":4,\"proposalId\":\"" + proposal + "\",\"proposalVersion\":1,\"reason\":\"Need another trace\"}"), "return resolution");
        var returnedList = developerBrowser.get("/api/developer/notifications");
        SessionAuthenticationTest.status(200, returnedList, "return notification");
        Map<?, ?> returnedItem = (Map<?, ?>) ((List<?>) SessionAuthenticationTest.json(returnedList).get("items")).get(0);
        assertEquals("RESOLUTION_RETURNED", returnedItem.get("type"));
        assertEquals(returnedReport.toString(), returnedItem.get("reportId"));
        assertFalse(returnedList.body().contains("Need another trace"));

        UUID reopenedReport = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version) VALUES(?,?,?, ?,?,'CLOSED',7)")) {
            insert.setObject(1, reopenedReport); insert.setString(2, "Reopen report"); insert.setString(3, "Private text");
            insert.setObject(4, reporter.id()); insert.setObject(5, developer.id()); insert.executeUpdate();
        }
        SessionAuthenticationTest.status(200, write(maintainerBrowser, "POST", "/api/maintainer/reports/" + reopenedReport + "/reopen",
                "{\"expectedVersion\":7,\"reason\":\"New evidence\"}"), "reopen report");
        var reopenedList = developerBrowser.get("/api/developer/notifications");
        SessionAuthenticationTest.status(200, reopenedList, "reopen notification");
        List<?> items = (List<?>) SessionAuthenticationTest.json(reopenedList).get("items");
        Map<?, ?> reopenedItem = (Map<?, ?>) items.get(0);
        assertEquals("REOPENED", reopenedItem.get("type"));
        assertNull(reopenedItem.get("reportId"));
        assertFalse(reopenedList.body().contains("New evidence"));
    }

    private static HttpResponse<String> write(SessionAuthenticationTest.Browser browser, String method,
            String path, String body) throws Exception {
        return browser.request(method, path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());
    }
}
