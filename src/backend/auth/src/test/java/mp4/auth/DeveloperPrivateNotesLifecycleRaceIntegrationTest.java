package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises private notes against real report reassignment, closure, and detail reads. */
class DeveloperPrivateNotesLifecycleRaceIntegrationTest {
    @BeforeAll static void startAuthenticatedServer() throws Exception {
        SessionAuthenticationTest.setup();
        try (var c = SessionAuthenticationTest.connection(); var statement = c.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS developer_private_notes ("
                    + "report_id uuid PRIMARY KEY REFERENCES reports(id), text text NOT NULL, "
                    + "revision bigint NOT NULL CHECK (revision >= 0))");
            statement.execute("CREATE TABLE IF NOT EXISTS private_notes_test_write_gate (report_id uuid PRIMARY KEY)");
            statement.execute("""
                    CREATE OR REPLACE FUNCTION private_notes_test_write_gate_fn() RETURNS trigger AS $$
                    BEGIN
                      PERFORM report_id FROM private_notes_test_write_gate
                        WHERE report_id = NEW.report_id FOR UPDATE;
                      RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            statement.execute("DROP TRIGGER IF EXISTS private_notes_test_write_gate ON developer_private_notes");
            statement.execute("""
                    CREATE TRIGGER private_notes_test_write_gate
                    BEFORE INSERT OR UPDATE ON developer_private_notes
                    FOR EACH ROW EXECUTE FUNCTION private_notes_test_write_gate_fn()
                    """);
        }
    }

    @AfterAll static void stopAuthenticatedServer() {
        SessionAuthenticationTest.shutdown();
    }

    @Test void notesWriteThenReassignmentCommitsBothWithoutLosingNotes() throws Exception {
        var f = fixture("reassignment-write-first");
        var browser = SessionAuthenticationTest.authenticated(f.first());
        try (WriteGate gate = holdWriteGate(f.report())) {
            CompletableFuture<HttpResponse<String>> write = asyncSave(browser, f.report(), "write wins", 1);
            int writerPid = awaitWriterBlockedByGate(gate.backendPid());
            CompletableFuture<Void> reassignment = asyncTransition(
                    "UPDATE reports SET assignee_id=? WHERE id=?", f.second().id(), f.report());
            awaitTransitionBlockedByWriter(writerPid, "UPDATE reports SET assignee_id");

            gate.release();
            assertSaved(write.get(15, TimeUnit.SECONDS), "write wins", 2);
            reassignment.get(15, TimeUnit.SECONDS);
        }
        assertReport(f.report(), "ASSIGNED", f.second().id());
        assertNotes(f.report(), "write wins", 2);
        var formerAssigneeRead = browser.get(notesPath(f.report()));
        SessionAuthenticationTest.error(formerAssigneeRead, 404, "NOT_FOUND");
    }

    @Test void reassignmentThenNotesWriteRejectsFormerAssigneeWithoutMutation() throws Exception {
        var f = fixture("reassignment-transition-first");
        var browser = SessionAuthenticationTest.authenticated(f.first());
        try (ReportTransition transition = lockAndTransition(
                f.report(), "UPDATE reports SET assignee_id=? WHERE id=?", f.second().id())) {
            CompletableFuture<HttpResponse<String>> write = asyncSave(browser, f.report(), "must not replace", 1);
            awaitNotesWriteBlockedByReport(transition.backendPid());

            transition.release();
            var response = write.get(15, TimeUnit.SECONDS);
            SessionAuthenticationTest.error(response, 404, "NOT_FOUND");
            assertFalse(response.body().contains("must not replace"));
        }
        assertReport(f.report(), "ASSIGNED", f.second().id());
        assertNotes(f.report(), "before", 1);
    }

    @Test void notesWriteThenClosureCommitsBothAndRetainsSavedNotes() throws Exception {
        var f = fixture("closure-write-first");
        var browser = SessionAuthenticationTest.authenticated(f.first());
        try (WriteGate gate = holdWriteGate(f.report())) {
            CompletableFuture<HttpResponse<String>> write = asyncSave(browser, f.report(), "write before close", 1);
            int writerPid = awaitWriterBlockedByGate(gate.backendPid());
            CompletableFuture<Void> closure = asyncTransition(
                    "UPDATE reports SET status='CLOSED' WHERE id=?", f.report());
            awaitTransitionBlockedByWriter(writerPid, "UPDATE reports SET status='CLOSED'");

            gate.release();
            assertSaved(write.get(15, TimeUnit.SECONDS), "write before close", 2);
            closure.get(15, TimeUnit.SECONDS);
        }
        assertReport(f.report(), "CLOSED", f.first().id());
        assertNotes(f.report(), "write before close", 2);
    }

    @Test void closureThenNotesWriteRejectsChangeWithoutMutation() throws Exception {
        var f = fixture("closure-transition-first");
        var browser = SessionAuthenticationTest.authenticated(f.first());
        try (ReportTransition transition = lockAndTransition(
                f.report(), "UPDATE reports SET status='CLOSED' WHERE id=?")) {
            CompletableFuture<HttpResponse<String>> write = asyncSave(browser, f.report(), "must not replace", 1);
            awaitNotesWriteBlockedByReport(transition.backendPid());

            transition.release();
            var response = write.get(15, TimeUnit.SECONDS);
            SessionAuthenticationTest.error(response, 409, "INVALID_STATE");
            assertFalse(response.body().contains("must not replace"));
        }
        assertReport(f.report(), "CLOSED", f.first().id());
        assertNotes(f.report(), "before", 1);
    }

    @Test void developerReportDetailOmitsPrivateNotesAndRevision() throws Exception {
        var f = fixture("detail-omits-private-notes");
        saveNotes(f.report(), "report-detail-private-canary", 7);
        var browser = SessionAuthenticationTest.authenticated(f.first());

        var response = browser.get("/api/developer/reports/" + f.report());
        SessionAuthenticationTest.status(200, response, "assigned Developer report detail");
        var detail = SessionAuthenticationTest.json(response);
        assertEquals(java.util.Set.of("id", "title", "description", "status", "version", "assigneeId", "returnReason"),
                detail.keySet());
        assertNull(detail.get("returnReason"));
        assertFalse(response.body().contains("report-detail-private-canary"));
        assertFalse(response.body().contains("notesRevision"));
    }

    private record Fixture(SessionAuthenticationTest.Account first, SessionAuthenticationTest.Account second,
                           SessionAuthenticationTest.Account reporter, UUID report) {}

    private static Fixture fixture(String title) throws Exception {
        var first = SessionAuthenticationTest.account("DEVELOPER");
        var second = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
            insert.setObject(1, report);
            insert.setString(2, title);
            insert.setString(3, "Disposable private notes lifecycle fixture");
            insert.setObject(4, reporter.id());
            insert.setObject(5, first.id());
            insert.executeUpdate();
        }
        saveNotes(report, "before", 1);
        return new Fixture(first, second, reporter, report);
    }

    private static void saveNotes(UUID report, String text, long revision) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO developer_private_notes(report_id,text,revision) VALUES(?,?,?) "
                        + "ON CONFLICT(report_id) DO UPDATE SET text=EXCLUDED.text, revision=EXCLUDED.revision")) {
            insert.setObject(1, report);
            insert.setString(2, text);
            insert.setLong(3, revision);
            insert.executeUpdate();
        }
    }

    private static CompletableFuture<HttpResponse<String>> asyncSave(
            SessionAuthenticationTest.Browser browser, UUID report, String text, long revision) throws Exception {
        String csrf = browser.csrf();
        String body = "{\"text\":\"" + text + "\",\"expectedRevision\":" + revision + "}";
        return CompletableFuture.supplyAsync(() -> {
            try {
                return browser.request("PUT", notesPath(report), body,
                        "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
    }

    private static CompletableFuture<Void> asyncTransition(String sql, Object... values) {
        return CompletableFuture.runAsync(() -> {
            try (var c = SessionAuthenticationTest.connection()) {
                c.setAutoCommit(false);
                try (var update = c.prepareStatement(sql)) {
                    for (int i = 0; i < values.length; i++) update.setObject(i + 1, values[i]);
                    assertEquals(1, update.executeUpdate());
                }
                c.commit();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
    }

    private static WriteGate holdWriteGate(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO private_notes_test_write_gate(report_id) VALUES(?) ON CONFLICT DO NOTHING")) {
            insert.setObject(1, report);
            insert.executeUpdate();
        }
        var c = SessionAuthenticationTest.connection();
        c.setAutoCommit(false);
        int pid;
        try (var query = c.prepareStatement("SELECT pg_backend_pid()")) {
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); pid = rows.getInt(1); }
        }
        try (var lock = c.prepareStatement("SELECT report_id FROM private_notes_test_write_gate WHERE report_id=? FOR UPDATE")) {
            lock.setObject(1, report);
            try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
        }
        return new WriteGate(c, pid);
    }

    private static ReportTransition lockAndTransition(UUID report, String sql, Object... values) throws Exception {
        var c = SessionAuthenticationTest.connection();
        c.setAutoCommit(false);
        int pid;
        try (var query = c.prepareStatement("SELECT pg_backend_pid()")) {
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); pid = rows.getInt(1); }
        }
        try (var lock = c.prepareStatement("SELECT id FROM reports WHERE id=? FOR UPDATE")) {
            lock.setObject(1, report);
            try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
        }
        try (var update = c.prepareStatement(sql)) {
            int parameter = 1;
            for (Object value : values) update.setObject(parameter++, value);
            update.setObject(parameter, report);
            assertEquals(1, update.executeUpdate());
        }
        return new ReportTransition(c, pid);
    }

    private static int awaitWriterBlockedByGate(int gatePid) throws Exception {
        return waitForBlockedBackend(gatePid, "INSERT INTO developer_private_notes", null);
    }

    private static void awaitTransitionBlockedByWriter(int writerPid, String queryText) throws Exception {
        waitForBlockedBackend(writerPid, queryText, null);
    }

    private static void awaitNotesWriteBlockedByReport(int reportLockPid) throws Exception {
        waitForBlockedBackend(reportLockPid, "FROM reports", "FOR UPDATE");
    }

    private static int waitForBlockedBackend(int blockerPid, String queryPart, String secondQueryPart)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement("""
                    SELECT activity.pid FROM pg_stat_activity activity
                    WHERE activity.datname = current_database()
                      AND activity.wait_event_type = 'Lock'
                      AND activity.query ILIKE ?
                      AND (? = '' OR activity.query ILIKE ?)
                      AND ? = ANY(pg_blocking_pids(activity.pid))
                    LIMIT 1
                    """)) {
                query.setString(1, "%" + queryPart + "%");
                query.setString(2, secondQueryPart == null ? "" : secondQueryPart);
                query.setString(3, secondQueryPart == null ? "" : "%" + secondQueryPart + "%");
                query.setInt(4, blockerPid);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) return rows.getInt(1);
                }
            }
            Thread.sleep(20);
        }
        fail("Expected PostgreSQL lock wait on query fragment: " + queryPart);
        return -1;
    }

    private static void assertSaved(HttpResponse<String> response, String text, long revision) {
        SessionAuthenticationTest.status(200, response, "notes save");
        var body = SessionAuthenticationTest.json(response);
        assertEquals(text, body.get("text"));
        assertEquals(revision, ((Number) body.get("revision")).longValue());
    }

    private static void assertNotes(UUID report, String text, long revision) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT text,revision FROM developer_private_notes WHERE report_id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(text, rows.getString(1));
                assertEquals(revision, rows.getLong(2));
            }
        }
    }

    private static void assertReport(UUID report, String status, UUID assignee) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT status,assignee_id FROM reports WHERE id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(status, rows.getString(1));
                assertEquals(assignee, rows.getObject(2, UUID.class));
            }
        }
    }

    private static String notesPath(UUID report) { return "/api/developer/reports/" + report + "/notes"; }

    private static class DbLock implements AutoCloseable {
        final Connection connection;
        final int backendPid;
        boolean released;

        DbLock(Connection connection, int backendPid) { this.connection = connection; this.backendPid = backendPid; }
        int backendPid() { return backendPid; }
        void release() throws Exception {
            if (!released) { connection.commit(); connection.close(); released = true; }
        }
        @Override public void close() throws Exception { if (!released) { connection.rollback(); connection.close(); released = true; } }
    }

    private static final class WriteGate extends DbLock {
        WriteGate(Connection connection, int backendPid) { super(connection, backendPid); }
    }

    private static final class ReportTransition extends DbLock {
        ReportTransition(Connection connection, int backendPid) { super(connection, backendPid); }
    }
}
