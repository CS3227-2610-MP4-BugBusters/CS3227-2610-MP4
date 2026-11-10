package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The shared authentication boundary must protect admitted Developer writes too. */
class DeveloperRevocationIntegrationTest {
    @BeforeAll static void start() throws Exception { SessionAuthenticationTest.setup(); }
    @AfterAll static void stop() { SessionAuthenticationTest.shutdown(); }

    @Test void accountDisableWinsBeforeChecklistSaveOrShare() throws Exception {
        for (boolean share : new boolean[]{false, true}) {
            var developer = SessionAuthenticationTest.account("DEVELOPER");
            var reporter = SessionAuthenticationTest.account("REPORTER");
            UUID report = UUID.randomUUID();
            try (var connection = SessionAuthenticationTest.connection()) {
                try (var insert = connection.prepareStatement("INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
                    insert.setObject(1, report); insert.setString(2, "Revocation boundary"); insert.setString(3, "Synthetic source");
                    insert.setObject(4, reporter.id()); insert.setObject(5, developer.id()); insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO investigation_checklists(report_id,draft_steps) VALUES(?,ARRAY['Draft'])")) {
                    insert.setObject(1, report); insert.executeUpdate();
                }
            }
            var browser = SessionAuthenticationTest.authenticated(developer);
            String token = browser.csrf();
            var workers = Executors.newSingleThreadExecutor();
            try (var blocker = SessionAuthenticationTest.connection()) {
                blocker.setAutoCommit(false);
                long blockerId;
                try (var query = blocker.prepareStatement("SELECT pg_backend_pid() FROM app_accounts WHERE id=? FOR UPDATE")) {
                    query.setObject(1, developer.id());
                    try (var row = query.executeQuery()) { assertTrue(row.next()); blockerId = row.getLong(1); }
                }
                var pending = workers.submit(() -> browser.request(share ? "POST" : "PUT",
                        "/api/reports/" + report + "/checklist/" + (share ? "share" : "draft"),
                        share ? "{\"expectedDraftRevision\":0,\"expectedReportVersion\":0}"
                                : "{\"expectedDraftRevision\":0,\"steps\":[\"Changed draft\"]}",
                        "Content-Type", "application/json", "X-CSRF-TOKEN", token));
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                try (var observer = SessionAuthenticationTest.connection(); var query = observer.prepareStatement(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))")) {
                    query.setLong(1, blockerId);
                    while (System.nanoTime() < deadline && !pending.isDone()) {
                        try (var row = query.executeQuery()) { row.next(); waiting = row.getBoolean(1); }
                        if (waiting) break;
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                    }
                }
                assertTrue(waiting, "the write must recheck eligibility before acquiring the report lock");
                try (var update = blocker.prepareStatement("UPDATE app_accounts SET active=false,auth_generation=auth_generation+1 WHERE id=?")) {
                    update.setObject(1, developer.id()); assertEquals(1, update.executeUpdate());
                }
                blocker.commit();
                SessionAuthenticationTest.error(pending.get(5, TimeUnit.SECONDS), 401, "UNAUTHENTICATED");
                try (var query = blocker.prepareStatement("SELECT r.version,c.draft_revision,c.shared_steps FROM reports r JOIN investigation_checklists c ON c.report_id=r.id WHERE r.id=?")) {
                    query.setObject(1, report);
                    try (var row = query.executeQuery()) {
                        assertTrue(row.next()); assertEquals(0, row.getLong("version")); assertEquals(0, row.getLong("draft_revision"));
                        assertEquals(0, ((String[]) row.getArray("shared_steps").getArray()).length);
                    }
                }
                blocker.rollback();
            } finally {
                workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
