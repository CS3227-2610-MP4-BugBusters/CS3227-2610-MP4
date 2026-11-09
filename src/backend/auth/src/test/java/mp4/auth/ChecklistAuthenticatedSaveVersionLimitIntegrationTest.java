package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Ensures an unsafe draft revision cannot be saved through the real session/API path. */
class ChecklistAuthenticatedSaveVersionLimitIntegrationTest {
    private static final long MAX_SAFE_VERSION = 9_007_199_254_740_991L;

    @BeforeAll static void startAuthenticatedServer() throws Exception {
        SessionAuthenticationTest.setup();
    }

    @AfterAll static void stopAuthenticatedServer() {
        SessionAuthenticationTest.shutdown();
    }

    @Test void maxSafeDraftRevisionRejectsSaveWithoutChangingAnyPersistedState() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        String[] originalDraft = {"Original private draft"};
        String[] originalShared = {"Previously published steps"};
        insertReportAndChecklist(report, reporter.id(), developer.id(), originalDraft, originalShared);

        var browser = SessionAuthenticationTest.authenticated(developer);
        String path = "/api/reports/" + report + "/checklist/draft";
        String body = "{\"steps\":[\"Replacement draft\"],\"expectedDraftRevision\":" + MAX_SAFE_VERSION + "}";
        var response = browser.request("PUT", path, body,
                "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());

        assertAll("unsafe draft revision is rejected without mutation",
                () -> SessionAuthenticationTest.error(response, 409, "VERSION_LIMIT"),
                () -> assertState(report, MAX_SAFE_VERSION, originalDraft, originalShared, 0));
    }

    private static void insertReportAndChecklist(UUID report, UUID reporter, UUID assignee,
            String[] draft, String[] shared) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version) VALUES(?,?,?,?,?,'ASSIGNED',0)")) {
            insert.setObject(1, report); insert.setString(2, "Draft version limit integration");
            insert.setString(3, "Disposable acceptance fixture"); insert.setObject(4, reporter);
            insert.setObject(5, assignee); insert.executeUpdate();
        }
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?::text[],?,?::text[])")) {
            insert.setObject(1, report); insert.setString(2, pgArray(draft));
            insert.setLong(3, MAX_SAFE_VERSION); insert.setString(4, pgArray(shared)); insert.executeUpdate();
        }
    }

    private static void assertState(UUID report, long revision, String[] draft, String[] shared,
            long reportVersion) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var query = c.prepareStatement("SELECT r.version,c.draft_revision,c.draft_steps,c.shared_steps FROM reports r JOIN investigation_checklists c ON c.report_id=r.id WHERE r.id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); assertEquals(reportVersion, rows.getLong(1));
                assertEquals(revision, rows.getLong(2));
                assertArrayEquals(draft, (String[]) rows.getArray(3).getArray());
                assertArrayEquals(shared, (String[]) rows.getArray(4).getArray());
            }
        }
    }

    private static String pgArray(String[] values) {
        if (values.length == 0) return "{}";
        return "{" + String.join(",", Arrays.stream(values)
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList()) + "}";
    }
}
