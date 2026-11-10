package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real-session PostgreSQL coverage for Developer report lifecycle writes. */
class DeveloperLifecycleHistoryIntegrationTest {
    @BeforeAll static void start() throws Exception { SessionAuthenticationTest.setup(); }
    @AfterAll static void stop() { SessionAuthenticationTest.shutdown(); }

    @Test void currentEligibilityWinsBeforeStartReportLock() throws Exception {
        assertRevocationWins(false);
    }

    @Test void currentEligibilityWinsBeforeProposalReportLock() throws Exception {
        assertRevocationWins(true);
    }

    @Test void startAndProposalAppendSharedHistoryAtCommittedReportVersion() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var browser = SessionAuthenticationTest.authenticated(developer);
        UUID started = createReport(reporter.id(), developer.id(), "ASSIGNED");
        var startResponse = browser.request("POST", "/api/developer/reports/" + started + "/start",
                "{\"expectedVersion\":0}", "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());
        SessionAuthenticationTest.status(200, startResponse, "start");

        UUID proposed = createReport(reporter.id(), developer.id(), "IN_PROGRESS");
        var proposalResponse = browser.request("POST", "/api/developer/reports/" + proposed + "/resolution",
                "{\"expectedVersion\":0,\"resolution\":\"Restore service\"}",
                "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());
        SessionAuthenticationTest.status(200, proposalResponse, "proposal");
        assertAll(() -> assertHistory(started, developer.id(), 1), () -> assertHistory(proposed, developer.id(), 1));
    }

    @Test void failedHistoryInsertRollsBackLifecycleAndProposalWrites() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var browser = SessionAuthenticationTest.authenticated(developer);
        var failures = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for (boolean proposal : new boolean[] {false, true}) {
            UUID report = createReport(reporter.id(), developer.id(), proposal ? "IN_PROGRESS" : "ASSIGNED");
            installHistoryFailure(report);
            try {
                var response = browser.request("POST", "/api/developer/reports/" + report
                                + (proposal ? "/resolution" : "/start"),
                        proposal ? "{\"expectedVersion\":0,\"resolution\":\"Restore service\"}"
                                : "{\"expectedVersion\":0}",
                        "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());
                failures.add(() -> SessionAuthenticationTest.status(503, response, "history failure"));
            } finally { removeHistoryFailure(report); }
            failures.add(() -> assertLifecycleUnchanged(report, proposal ? "IN_PROGRESS" : "ASSIGNED", 0));
            if (proposal) failures.add(() -> assertEquals(0, proposalCount(report)));
        }
        assertAll(failures);
    }

    private static void assertRevocationWins(boolean proposal) throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = createReport(reporter.id(), developer.id(), proposal ? "IN_PROGRESS" : "ASSIGNED");
        var browser = SessionAuthenticationTest.authenticated(developer);
        String path = "/api/developer/reports/" + report + (proposal ? "/resolution" : "/start");
        String body = proposal
                ? "{\"expectedVersion\":0,\"resolution\":\"Restore service\"}"
                : "{\"expectedVersion\":0}";
        var workers = Executors.newSingleThreadExecutor();
        try (var blocker = SessionAuthenticationTest.connection()) {
            blocker.setAutoCommit(false);
            long blockerPid;
            try (var query = blocker.prepareStatement("SELECT pg_backend_pid() FROM app_accounts WHERE id=? FOR UPDATE")) {
                query.setObject(1, developer.id());
                try (var row = query.executeQuery()) { assertTrue(row.next()); blockerPid = row.getLong(1); }
            }
            var pending = workers.submit(() -> browser.request("POST", path, body,
                    "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf()));
            assertTrue(awaitBlockedBy(blockerPid, pending),
                    "lifecycle write must acquire current account eligibility before its report lock");
            try (var update = blocker.prepareStatement(
                    "UPDATE app_accounts SET active=false,auth_generation=auth_generation+1 WHERE id=?")) {
                update.setObject(1, developer.id()); assertEquals(1, update.executeUpdate());
            }
            blocker.commit();
            SessionAuthenticationTest.error(pending.get(5, TimeUnit.SECONDS), 401, "UNAUTHENTICATED");
        } finally { workers.shutdownNow(); }
        assertLifecycleUnchanged(report, proposal ? "IN_PROGRESS" : "ASSIGNED", 0);
    }

    private static UUID createReport(UUID reporter, UUID assignee, String status) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?, ?,?,?,?)")) {
            insert.setObject(1, id); insert.setString(2, "Lifecycle fixture"); insert.setString(3, "Synthetic description");
            insert.setObject(4, reporter); insert.setObject(5, assignee); insert.setString(6, status); insert.executeUpdate();
        }
        return id;
    }

    private static boolean awaitBlockedBy(long blockerPid, java.util.concurrent.Future<?> pending) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (var observer = SessionAuthenticationTest.connection(); var query = observer.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))")) {
            query.setLong(1, blockerPid);
            while (System.nanoTime() < deadline && !pending.isDone()) {
                try (var row = query.executeQuery()) { row.next(); if (row.getBoolean(1)) return true; }
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
        }
        return false;
    }

    private static void assertLifecycleUnchanged(UUID report, String expectedStatus, long expectedVersion) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT status,version,(SELECT count(*) FROM report_history WHERE report_id=r.id) FROM reports r WHERE id=?")) {
            query.setObject(1, report);
            try (var row = query.executeQuery()) {
                assertTrue(row.next()); assertEquals(expectedStatus, row.getString(1));
                assertEquals(expectedVersion, row.getLong(2)); assertEquals(0, row.getInt(3));
            }
        }
    }

    private static void assertHistory(UUID report, UUID actor, long version) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT actor_id,report_version FROM report_history WHERE report_id=?")) {
            query.setObject(1, report);
            try (var row = query.executeQuery()) {
                assertTrue(row.next(), "lifecycle transition must append shared history");
                assertEquals(actor, row.getObject(1, UUID.class)); assertEquals(version, row.getLong(2));
                assertFalse(row.next(), "one lifecycle transition appends one history event");
            }
        }
    }

    private static long proposalCount(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT count(*) FROM resolution_proposals WHERE report_id=?")) {
            query.setObject(1, report); try (var row = query.executeQuery()) { row.next(); return row.getLong(1); }
        }
    }

    private static void installHistoryFailure(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var statement = c.createStatement()) {
            statement.execute("CREATE FUNCTION reject_lifecycle_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.report_id='"
                    + report + "' THEN RAISE EXCEPTION 'injected history failure'; END IF; RETURN NEW; END $$");
            statement.execute("CREATE TRIGGER reject_lifecycle_history BEFORE INSERT ON report_history FOR EACH ROW EXECUTE FUNCTION reject_lifecycle_history()");
        }
    }

    private static void removeHistoryFailure(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var statement = c.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS reject_lifecycle_history ON report_history");
            statement.execute("DROP FUNCTION IF EXISTS reject_lifecycle_history()");
        }
    }
}
