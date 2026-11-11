package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ResolutionAuthenticatedIntegrationTest {
    @BeforeAll static void setup() throws Exception { SessionAuthenticationTest.setup(); }
    @AfterAll static void shutdown() { SessionAuthenticationTest.shutdown(); }

    @Test void onlyAssignedDeveloperCanSubmitOnceAndFailuresPreserveState() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var other = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection(); var s = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version) VALUES (?, 'title', 'description', ?, ?, 'IN_PROGRESS', 4)")) {
            s.setObject(1, report); s.setObject(2, reporter.id()); s.setObject(3, developer.id()); s.executeUpdate();
        }
        String path = "/api/developer/reports/" + report + "/resolution";
        var browser = SessionAuthenticationTest.authenticated(developer);
        var stranger = SessionAuthenticationTest.authenticated(other);
        String token = browser.csrf();
        String json = "{\"expectedVersion\":4,\"resolution\":\"Restart the service\"}";
        SessionAuthenticationTest.status(409, stranger.request("POST", path, json, "Content-Type", "application/json", "X-CSRF-TOKEN", stranger.csrf()), "unassigned");
        SessionAuthenticationTest.status(400, browser.request("POST", path, "{\"expectedVersion\":4,\"resolution\":\"  \"}", "Content-Type", "application/json", "X-CSRF-TOKEN", token), "empty");
        SessionAuthenticationTest.status(409, browser.request("POST", path, "{\"expectedVersion\":3,\"resolution\":\"x\"}", "Content-Type", "application/json", "X-CSRF-TOKEN", token), "stale");
        SessionAuthenticationTest.status(200, browser.request("POST", path, json, "Content-Type", "application/json", "X-CSRF-TOKEN", token), "submit");
        SessionAuthenticationTest.status(409, browser.request("POST", path, json, "Content-Type", "application/json", "X-CSRF-TOKEN", token), "duplicate");
        try (var c = SessionAuthenticationTest.connection(); var s = c.prepareStatement(
                "SELECT r.status, r.version, p.status, p.contents, (SELECT count(*) FROM resolution_proposals WHERE report_id = r.id) FROM reports r JOIN resolution_proposals p ON p.id = r.current_proposal_id WHERE r.id = ?")) {
            s.setObject(1, report);
            try (var rows = s.executeQuery()) {
                assertTrue(rows.next()); assertEquals("RESOLUTION_PROPOSED", rows.getString(1)); assertEquals(5, rows.getLong(2));
                assertEquals("PENDING", rows.getString(3)); assertEquals("Restart the service", rows.getString(4)); assertEquals(1, rows.getInt(5));
            }
        }
    }
}
