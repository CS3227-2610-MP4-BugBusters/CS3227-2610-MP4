package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Ensures an in-flight notes write rechecks account activity after its report lock wait. */
class DeveloperPrivateNotesDeactivationRaceIntegrationTest {
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

    @Test void deactivationWhileWriteWaitsForReportLockRejectsWrite() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
            insert.setObject(1, report);
            insert.setString(2, "Private notes deactivation race");
            insert.setString(3, "Disposable lock-race fixture");
            insert.setObject(4, reporter.id());
            insert.setObject(5, developer.id());
            insert.executeUpdate();
        }

        var browser = SessionAuthenticationTest.authenticated(developer);
        String path = "/api/developer/reports/" + report + "/notes";
        String csrf = browser.csrf();
        try (ReportRowLock reportLock = lockReport(report);
             AccountDeactivation deactivation = beginDeactivation(developer.id())) {
            CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return browser.request("PUT", path,
                            "{\"text\":\"must not persist\",\"expectedRevision\":0}",
                            "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            awaitWriteLockWait(reportLock.backendPid(), deactivation.backendPid());

            deactivation.commit();
            reportLock.release();
            var response = pending.get(15, TimeUnit.SECONDS);
            SessionAuthenticationTest.error(response, 403, "FORBIDDEN");
            assertFalse(response.body().contains("must not persist"));
            assertEquals(0L, notesRowCount(report), "deactivated request must leave no notes row");
        }
    }

    private static long notesRowCount(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT count(*) FROM developer_private_notes WHERE report_id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    private static ReportRowLock lockReport(UUID report) throws Exception {
        var connection = SessionAuthenticationTest.connection();
        connection.setAutoCommit(false);
        int backendPid;
        try (var query = connection.prepareStatement("SELECT pg_backend_pid()")) {
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); backendPid = rows.getInt(1); }
        }
        try (var query = connection.prepareStatement("SELECT id FROM reports WHERE id=? FOR UPDATE")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); }
        }
        return new ReportRowLock(connection, backendPid);
    }

    private static AccountDeactivation beginDeactivation(UUID account) throws Exception {
        var connection = SessionAuthenticationTest.connection();
        connection.setAutoCommit(false);
        int backendPid;
        try (var query = connection.prepareStatement("SELECT pg_backend_pid()")) {
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); backendPid = rows.getInt(1); }
        }
        try (var update = connection.prepareStatement("UPDATE app_accounts SET active=false WHERE id=?")) {
            update.setObject(1, account);
            assertEquals(1, update.executeUpdate());
        }
        return new AccountDeactivation(connection, backendPid);
    }

    private static void awaitWriteLockWait(int reportBlockerPid, int accountBlockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity activity
                        WHERE activity.datname = current_database()
                          AND activity.wait_event_type = 'Lock'
                          AND (activity.query ILIKE '%FROM reports%FOR UPDATE%'
                               OR activity.query ILIKE '%FROM app_accounts%')
                          AND (? = ANY(pg_blocking_pids(activity.pid))
                               OR ? = ANY(pg_blocking_pids(activity.pid)))
                    )
                    """)) {
                query.setInt(1, reportBlockerPid);
                query.setInt(2, accountBlockerPid);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    if (rows.getBoolean(1)) return;
                }
            }
            Thread.sleep(20);
        }
        fail("notes save never appeared in PostgreSQL report-row lock wait state");
    }

    private static final class ReportRowLock implements AutoCloseable {
        private final java.sql.Connection connection;
        private final int backendPid;
        private boolean released;

        private ReportRowLock(java.sql.Connection connection, int backendPid) {
            this.connection = connection;
            this.backendPid = backendPid;
        }

        int backendPid() { return backendPid; }

        void release() throws Exception {
            if (!released) {
                connection.rollback();
                connection.close();
                released = true;
            }
        }

        @Override public void close() throws Exception { release(); }
    }

    private static final class AccountDeactivation implements AutoCloseable {
        private final java.sql.Connection connection;
        private final int backendPid;
        private boolean committed;

        private AccountDeactivation(java.sql.Connection connection, int backendPid) {
            this.connection = connection;
            this.backendPid = backendPid;
        }

        int backendPid() { return backendPid; }

        void commit() throws Exception {
            connection.commit();
            committed = true;
        }

        @Override public void close() throws Exception {
            if (!committed) connection.rollback();
            connection.close();
        }
    }
}
