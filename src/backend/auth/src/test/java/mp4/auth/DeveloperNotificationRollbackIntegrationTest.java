package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies a notification write failure rolls back the report lifecycle transaction. */
class DeveloperNotificationRollbackIntegrationTest {
    @BeforeAll static void startServer() throws Exception { SessionAuthenticationTest.setup(); }
    @AfterAll static void stopServer() { SessionAuthenticationTest.shutdown(); }

    @Test void failedAssignmentNotificationRollsBackReportAndHistory() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        try (var connection = SessionAuthenticationTest.connection(); var insert = connection.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,status,version) VALUES(?,?,?,?,'SUBMITTED',0)")) {
            insert.setObject(1, report);
            insert.setString(2, "Notification rollback fixture");
            insert.setString(3, "No private content");
            insert.setObject(4, reporter.id());
            insert.executeUpdate();
        }

        try (Connection connection = SessionAuthenticationTest.connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION public.mp4_test_reject_notification_insert() RETURNS trigger
                    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'notification insert rejected by test'; END $$
                    """);
            statement.execute("""
                    CREATE TRIGGER mp4_test_reject_notification_insert
                    BEFORE INSERT ON public.developer_notifications
                    FOR EACH ROW EXECUTE FUNCTION public.mp4_test_reject_notification_insert()
                    """);
        }

        try {
            var browser = SessionAuthenticationTest.authenticated(maintainer);
            var response = browser.request("POST", "/api/maintainer/reports/" + report + "/assignment",
                    "{\"expectedVersion\":0,\"developerId\":\"" + developer.id() + "\"}",
                    "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());
            SessionAuthenticationTest.error(response, 503, "TEMPORARILY_UNAVAILABLE");
            assertFalse(response.body().contains("notification insert rejected by test"));
        } finally {
            try (Connection connection = SessionAuthenticationTest.connection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS mp4_test_reject_notification_insert ON public.developer_notifications");
                statement.execute("DROP FUNCTION IF EXISTS public.mp4_test_reject_notification_insert()");
            }
        }

        try (Connection connection = SessionAuthenticationTest.connection(); var query = connection.prepareStatement(
                "SELECT r.status,r.version,r.assignee_id,(SELECT count(*) FROM report_history h WHERE h.report_id=r.id),"
                        + "(SELECT count(*) FROM developer_notifications n WHERE n.report_id=r.id) FROM reports r WHERE r.id=?")) {
            query.setObject(1, report);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals("SUBMITTED", row.getString(1));
                assertEquals(0, row.getLong(2));
                assertNull(row.getObject(3));
                assertEquals(0, row.getLong(4));
                assertEquals(0, row.getLong(5));
            }
        }
    }
}
