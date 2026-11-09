package mp4.developer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.postgresql.ds.PGSimpleDataSource;

import static mp4.developer.ChecklistDraftStore.Role;
import static mp4.developer.ChecklistShareService.Outcome;

/** Deterministic local Share/closure lock-order check using a synthetic SQL closure transaction. */
public final class ChecklistShareClosureRaceRegressionTest {
    private static final UUID REPORT = id("501");
    private static final UUID REPORTER = id("502");
    private static final UUID DEVELOPER = id("503");
    private static final UUID PROPOSAL = id("504");
    private static final long REPORT_VERSION = 7;
    private static final long DRAFT_REVISION = 4;
    private static final ChecklistDraftStore.Actor ACTOR =
            new ChecklistDraftStore.Actor(DEVELOPER, Role.DEVELOPER, true);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <jdbc-url>");
        PGSimpleDataSource admin = source(args[0], "race-test-admin");
        PGSimpleDataSource shareDb = source(args[0], "share-worker");
        PGSimpleDataSource closureDb = source(args[0], "closure-worker");

        runOrder(admin, shareDb, closureDb, true);
        runOrder(admin, shareDb, closureDb, false);
        System.out.println("Share/closure race checks passed: Share-first made closure stale; closure-first denied Share and preserved the publication.");
    }

    private static void runOrder(PGSimpleDataSource admin, PGSimpleDataSource shareDb,
            PGSimpleDataSource closureDb, boolean shareFirst) throws Exception {
        seed(admin);
        var service = new JdbcChecklistShareService(shareDb);
        var pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = admin.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM reports WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, REPORT);
                lock.executeQuery().close();
            }

            Callable<ChecklistShareService.ShareResult> share = () -> service.share(
                    new ChecklistShareService.ShareRequest(ACTOR, REPORT, DRAFT_REVISION, REPORT_VERSION));
            Callable<String> close = () -> syntheticClosure(closureDb);
            var first = shareFirst ? pool.submit(share) : pool.submit(close);
            String firstApp = shareFirst ? "share-worker" : "closure-worker";
            awaitLockWait(admin, firstApp);
            var second = shareFirst ? pool.submit(close) : pool.submit(share);
            String secondApp = shareFirst ? "closure-worker" : "share-worker";
            awaitLockWait(admin, secondApp);

            blocker.commit();
            Object firstResult = first.get(10, TimeUnit.SECONDS);
            Object secondResult = second.get(10, TimeUnit.SECONDS);
            var shareResult = (ChecklistShareService.ShareResult) (shareFirst ? firstResult : secondResult);
            String closureResult = (String) (shareFirst ? secondResult : firstResult);
            Snapshot result = readSnapshot(admin);

            if (shareFirst) {
                expect(Outcome.SHARED, shareResult.outcome());
                expect(8L, shareResult.reportVersion());
                expect("STALE", closureResult);
                expect("IN_PROGRESS", result.reportStatus());
                expect(8L, result.reportVersion());
                expect("SUPERSEDED", result.proposalStatus());
                expect(List.of("new publication"), result.sharedSteps());
            } else {
                expect("CLOSED", closureResult);
                expect(Outcome.INVALID_STATE, shareResult.outcome());
                expect(null, shareResult.reportVersion());
                expect("CLOSED", result.reportStatus());
                expect(8L, result.reportVersion());
                expect("CLOSED", result.proposalStatus());
                expect(List.of("old publication"), result.sharedSteps());
            }
            expect(List.of("new publication"), result.draftSteps());
            expect(DRAFT_REVISION, result.draftRevision());
        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("Worker did not terminate");
        }
    }

    /** Test-only closure approximation: takes the same report lock and checks the observed version. */
    private static String syntheticClosure(PGSimpleDataSource db) throws SQLException {
        try (Connection connection = db.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                String status;
                long version;
                try (var lock = connection.prepareStatement(
                        "SELECT id FROM reports WHERE id = ? FOR UPDATE /* synthetic-closure-lock */")) {
                    lock.setObject(1, REPORT);
                    try (var row = lock.executeQuery()) {
                        if (!row.next()) throw new SQLException("Report disappeared");
                    }
                }
                try (var read = connection.prepareStatement("SELECT status, version FROM reports WHERE id = ?")) {
                    read.setObject(1, REPORT);
                    try (var row = read.executeQuery()) {
                        row.next();
                        status = row.getString("status");
                        version = row.getLong("version");
                    }
                }
                if (!"RESOLUTION_PROPOSED".equals(status) || version != REPORT_VERSION) {
                    connection.commit();
                    return "STALE";
                }
                try (var proposal = connection.prepareStatement(
                        "UPDATE resolution_proposals SET status = 'CLOSED' WHERE id = ? AND report_id = ? AND status = 'PENDING'")) {
                    proposal.setObject(1, PROPOSAL);
                    proposal.setObject(2, REPORT);
                    expect(1, proposal.executeUpdate());
                }
                try (var update = connection.prepareStatement(
                        "UPDATE reports SET status = 'CLOSED', version = ? WHERE id = ?")) {
                    update.setLong(1, REPORT_VERSION + 1);
                    update.setObject(2, REPORT);
                    expect(1, update.executeUpdate());
                }
                connection.commit();
                return "CLOSED";
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void awaitLockWait(PGSimpleDataSource admin, String applicationName) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (var c = admin.getConnection(); var q = c.prepareStatement("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE application_name = ? AND wait_event_type = 'Lock' AND state = 'active'
                    """)) {
                q.setString(1, applicationName);
                try (var row = q.executeQuery()) {
                    row.next();
                    if (row.getInt(1) > 0) return;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Did not observe " + applicationName + " waiting on a PostgreSQL lock");
    }

    private static void seed(PGSimpleDataSource db) throws SQLException {
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("TRUNCATE investigation_checklists, reports, resolution_proposals CASCADE");
        }
        update(db, "INSERT INTO resolution_proposals VALUES (?, ?, 'PENDING', 2, 'pending resolution')",
                PROPOSAL, REPORT);
        update(db, "INSERT INTO reports VALUES (?, ?, ?, 'RESOLUTION_PROPOSED', ?, ?)",
                REPORT, REPORTER, DEVELOPER, REPORT_VERSION, PROPOSAL);
        try (var c = db.getConnection()) {
            var draft = c.createArrayOf("text", new String[] {"new publication"});
            var shared = c.createArrayOf("text", new String[] {"old publication"});
            try (var s = c.prepareStatement("INSERT INTO investigation_checklists VALUES (?, ?, ?, ?)")) {
                s.setObject(1, REPORT);
                s.setArray(2, draft);
                s.setLong(3, DRAFT_REVISION);
                s.setArray(4, shared);
                s.executeUpdate();
            } finally {
                draft.free();
                shared.free();
            }
        }
    }

    private static Snapshot readSnapshot(PGSimpleDataSource db) throws SQLException {
        try (var c = db.getConnection(); var s = c.prepareStatement("""
                SELECT reports.status, reports.version, resolution_proposals.status AS proposal_status,
                       draft_steps, draft_revision, shared_steps
                FROM reports JOIN resolution_proposals ON resolution_proposals.id = current_proposal_id
                JOIN investigation_checklists ON investigation_checklists.report_id = reports.id
                WHERE reports.id = ?
                """)) {
            s.setObject(1, REPORT);
            try (var row = s.executeQuery()) {
                if (!row.next()) throw new AssertionError("Missing post-race report state");
                return new Snapshot(row.getString("status"), row.getLong("version"),
                        row.getString("proposal_status"), List.of((String[]) row.getArray("draft_steps").getArray()),
                        row.getLong("draft_revision"), List.of((String[]) row.getArray("shared_steps").getArray()));
            }
        }
    }

    private static PGSimpleDataSource source(String url, String applicationName) {
        var source = new PGSimpleDataSource();
        source.setURL(url + "&ApplicationName=" + applicationName);
        return source;
    }

    private static void update(PGSimpleDataSource db, String sql, Object... values) throws SQLException {
        try (var c = db.getConnection(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
        }
    }

    private static UUID id(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-000000000" + suffix);
    }

    private static void expect(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private record Snapshot(String reportStatus, long reportVersion, String proposalStatus,
            List<String> draftSteps, long draftRevision, List<String> sharedSteps) {}
}
