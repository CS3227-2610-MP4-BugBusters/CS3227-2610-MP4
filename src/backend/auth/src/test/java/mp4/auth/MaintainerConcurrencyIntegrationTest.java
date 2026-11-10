package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real-session and PostgreSQL race checks for the Maintainer aggregate transaction contract. */
class MaintainerConcurrencyIntegrationTest {
    private static final long WAIT_SECONDS = 5;

    @BeforeAll static void startServer() throws Exception {
        SessionAuthenticationTest.setup();
    }

    @AfterAll static void stopServer() {
        SessionAuthenticationTest.shutdown();
    }

    @Test void concurrentPriorityWritesAtOneVersionCommitExactlyOnce() throws Exception {
        var firstMaintainer = SessionAuthenticationTest.account("MAINTAINER");
        var secondMaintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = report(reporter.id(), null, "SUBMITTED", 0, "MEDIUM");
        var first = SessionAuthenticationTest.authenticated(firstMaintainer);
        var second = SessionAuthenticationTest.authenticated(secondMaintainer);
        String path = "/api/maintainer/reports/" + report + "/priority";
        String firstToken = first.csrf();
        String secondToken = second.csrf();

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Connection blocker = lockReport(report)) {
            Future<HttpResponse<String>> a = workers.submit(() -> priority(first, path, firstToken, "HIGH"));
            awaitBlockedCount(blockerPid(blocker), 1);
            Future<HttpResponse<String>> b = workers.submit(() -> priority(second, path, secondToken, "CRITICAL"));
            awaitBlockedCount(blockerPid(blocker), 2);
            blocker.commit();

            HttpResponse<String> resultA = a.get(WAIT_SECONDS, TimeUnit.SECONDS);
            HttpResponse<String> resultB = b.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(List.of(200, 409), List.of(resultA.statusCode(), resultB.statusCode()).stream().sorted().toList());
            HttpResponse<String> stale = resultA.statusCode() == 409 ? resultA : resultB;
            assertEquals("STALE_VERSION", SessionAuthenticationTest.json(stale).get("code"));
            assertEquals(1, reportVersion(report));
            assertEquals(1, historyCount(report));
            assertTrue(List.of("HIGH", "CRITICAL").contains(reportPriority(report)));
        } finally {
            stopWorkers(workers);
        }
    }

    @Test void historyInsertFailureRollsBackTheReportMutation() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = report(reporter.id(), null, "SUBMITTED", 4, "LOW");
        String trigger = "maintainer_fail_history_" + report.toString().replace("-", "");
        createHistoryFailureTrigger(trigger, report);
        try {
            var browser = SessionAuthenticationTest.authenticated(maintainer);
            HttpResponse<String> response = put(browser,
                    "/api/maintainer/reports/" + report + "/priority",
                    "{\"expectedVersion\":4,\"priority\":\"HIGH\"}");
            SessionAuthenticationTest.error(response, 503, "TEMPORARILY_UNAVAILABLE");
            assertEquals("SUBMITTED", reportStatus(report));
            assertEquals("LOW", reportPriority(report));
            assertEquals(4, reportVersion(report));
            assertEquals(0, historyCount(report));
        } finally {
            dropHistoryFailureTrigger(trigger);
        }
    }

    @Test void actorDeactivationAndMutationSerializeInBothCommitOrders() throws Exception {
        // If deactivation owns the eligibility row first, the mutation must be denied.
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = report(reporter.id(), null, "SUBMITTED", 0, "MEDIUM");
        var browser = SessionAuthenticationTest.authenticated(maintainer);
        String token = browser.csrf();
        ExecutorService firstWorkers = Executors.newFixedThreadPool(1);
        try (Connection deactivation = lockAccount(maintainer.id())) {
            Future<HttpResponse<String>> mutation = firstWorkers.submit(() -> priority(browser,
                    "/api/maintainer/reports/" + report + "/priority", token, "HIGH"));
            awaitBlockedCount(blockerPid(deactivation), 1);
            deactivateAndCommit(deactivation, maintainer.id());
            // The admitted actor snapshot became invalid while waiting for its eligibility lock.
            SessionAuthenticationTest.error(mutation.get(WAIT_SECONDS, TimeUnit.SECONDS), 401, "UNAUTHENTICATED");
            assertEquals(0, reportVersion(report));
            assertEquals(0, historyCount(report));
        } finally {
            stopWorkers(firstWorkers);
        }

        // If the mutation owns the eligibility row first, deactivation waits and commits after it.
        var secondMaintainer = SessionAuthenticationTest.account("MAINTAINER");
        var secondReporter = SessionAuthenticationTest.account("REPORTER");
        UUID secondReport = report(secondReporter.id(), null, "SUBMITTED", 0, "MEDIUM");
        var secondBrowser = SessionAuthenticationTest.authenticated(secondMaintainer);
        String secondToken = secondBrowser.csrf();
        ExecutorService secondWorkers = Executors.newFixedThreadPool(2);
        try (Connection reportBlocker = lockReport(secondReport)) {
            long reportBlockerPid = blockerPid(reportBlocker);
            Future<HttpResponse<String>> mutation = secondWorkers.submit(() -> priority(secondBrowser,
                    "/api/maintainer/reports/" + secondReport + "/priority", secondToken, "HIGH"));
            long mutationPid = awaitBlockedPid(reportBlockerPid);
            AtomicLong deactivationPid = new AtomicLong();
            Future<?> deactivation = secondWorkers.submit(() -> {
                try (Connection c = SessionAuthenticationTest.connection(); var statement = c.createStatement()) {
                    statement.execute("SET application_name = 'maintainer-deactivation-race'");
                    try (var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                        row.next();
                        deactivationPid.set(row.getLong(1));
                    }
                    try (var update = c.prepareStatement("UPDATE app_accounts SET active=false WHERE id=?")) {
                        update.setObject(1, secondMaintainer.id());
                        assertEquals(1, update.executeUpdate());
                    }
                }
                return null;
            });
            awaitBlockedBy(deactivationPid, mutationPid);
            reportBlocker.commit();
            SessionAuthenticationTest.status(200, mutation.get(WAIT_SECONDS, TimeUnit.SECONDS), "mutation wins eligibility race");
            deactivation.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(false, accountActive(secondMaintainer.id()));
            assertEquals(1, reportVersion(secondReport));
            assertEquals(1, historyCount(secondReport));
        } finally {
            stopWorkers(secondWorkers);
        }
    }

    @Test void changedChecklistShareAndRealMaintainerCloseSerializeInBothOrders() throws Exception {
        shareCloseOrder(true);
        shareCloseOrder(false);
    }

    @Test void targetDeactivationAndAssignmentSerializeInBothOrders() throws Exception {
        for (boolean deactivateFirst : List.of(true, false)) {
            var maintainer = SessionAuthenticationTest.account("MAINTAINER");
            var developer = SessionAuthenticationTest.account("DEVELOPER");
            var reporter = SessionAuthenticationTest.account("REPORTER");
            UUID report = report(reporter.id(), null, "SUBMITTED", 0, "MEDIUM");
            var browser = SessionAuthenticationTest.authenticated(maintainer);
            String token = browser.csrf();
            String path = "/api/maintainer/reports/" + report + "/assignment";
            String body = "{\"expectedVersion\":0,\"developerId\":\"" + developer.id() + "\"}";
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                if (deactivateFirst) {
                    try (Connection blocker = lockAccount(developer.id())) {
                        var pending = workers.submit(() -> post(browser, path, body, token));
                        awaitBlockedCount(blockerPid(blocker), 1);
                        deactivateAndCommit(blocker, developer.id());
                        SessionAuthenticationTest.error(pending.get(WAIT_SECONDS, TimeUnit.SECONDS), 400, "VALIDATION_FAILED");
                        assertEquals(0, reportVersion(report));
                        assertEquals(0, historyCount(report));
                    }
                } else {
                    try (Connection blocker = lockReport(report)) {
                        var pending = workers.submit(() -> post(browser, path, body, token));
                        long mutation = awaitBlockedPid(blockerPid(blocker));
                        AtomicLong disabling = new AtomicLong();
                        var deactivation = workers.submit(() -> {
                            try (var connection = SessionAuthenticationTest.connection()) {
                                disabling.set(blockerPid(connection));
                                try (var update = connection.prepareStatement("UPDATE app_accounts SET active=false WHERE id=?")) {
                                    update.setObject(1, developer.id()); assertEquals(1, update.executeUpdate());
                                }
                            }
                            return null;
                        });
                        awaitBlockedBy(disabling, mutation);
                        blocker.commit();
                        SessionAuthenticationTest.status(200, pending.get(WAIT_SECONDS, TimeUnit.SECONDS), "assignment wins eligibility race");
                        deactivation.get(WAIT_SECONDS, TimeUnit.SECONDS);
                        assertFalse(accountActive(developer.id()));
                        assertEquals(1, reportVersion(report));
                        assertEquals(1, historyCount(report));
                    }
                }
            } finally { stopWorkers(workers); }
        }
    }

    private static void shareCloseOrder(boolean shareFirst) throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = report(reporter.id(), developer.id(), "RESOLUTION_PROPOSED", 7, "MEDIUM");
        UUID proposal = proposal(report, 2);
        setCurrentProposal(report, proposal);
        checklist(report, new String[] {"new shared step"}, 3, new String[] {"old shared step"});
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        var developerBrowser = SessionAuthenticationTest.authenticated(developer);
        String maintainerToken = maintainerBrowser.csrf();
        String developerToken = developerBrowser.csrf();
        String closePath = "/api/maintainer/reports/" + report + "/close";
        String sharePath = "/api/reports/" + report + "/checklist/share";
        String closeBody = "{\"expectedVersion\":7,\"proposalId\":\"" + proposal
                + "\",\"proposalVersion\":2,\"reviewed\":true}";
        String shareBody = "{\"expectedDraftRevision\":3,\"expectedReportVersion\":7}";

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Connection blocker = lockReport(report)) {
            long blockerPid = blockerPid(blocker);
            Callable<HttpResponse<String>> first = shareFirst
                    ? () -> post(developerBrowser, sharePath, shareBody, developerToken)
                    : () -> post(maintainerBrowser, closePath, closeBody, maintainerToken);
            Callable<HttpResponse<String>> second = shareFirst
                    ? () -> post(maintainerBrowser, closePath, closeBody, maintainerToken)
                    : () -> post(developerBrowser, sharePath, shareBody, developerToken);
            Future<HttpResponse<String>> firstRequest = workers.submit(first);
            awaitBlockedCount(blockerPid, 1);
            Future<HttpResponse<String>> secondRequest = workers.submit(second);
            awaitBlockedCount(blockerPid, 2);
            blocker.commit();

            HttpResponse<String> firstResponse = firstRequest.get(WAIT_SECONDS, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = secondRequest.get(WAIT_SECONDS, TimeUnit.SECONDS);
            HttpResponse<String> shareResponse = shareFirst ? firstResponse : secondResponse;
            HttpResponse<String> closeResponse = shareFirst ? secondResponse : firstResponse;
            if (shareFirst) {
                SessionAuthenticationTest.status(200, shareResponse, "changed checklist Share wins");
                SessionAuthenticationTest.error(closeResponse, 409, "STALE_VERSION");
                assertEquals("IN_PROGRESS", reportStatus(report));
                assertEquals("SUPERSEDED", proposalStatus(proposal));
                assertEquals(List.of("new shared step"), sharedSteps(report));
            } else {
                SessionAuthenticationTest.status(200, closeResponse, "Maintainer close wins");
                SessionAuthenticationTest.error(shareResponse, 409, "INVALID_STATE");
                assertEquals("CLOSED", reportStatus(report));
                assertEquals("CLOSED", proposalStatus(proposal));
                assertEquals(List.of("old shared step"), sharedSteps(report));
            }
            assertEquals(8, reportVersion(report));
            assertEquals(1, historyCount(report));
            assertEquals(3, draftRevision(report));
            assertEquals(List.of("new shared step"), draftSteps(report));
        } finally {
            stopWorkers(workers);
        }
    }

    private static HttpResponse<String> priority(SessionAuthenticationTest.Browser browser,
            String path, String token, String value) throws Exception {
        return put(browser, path, "{\"expectedVersion\":0,\"priority\":\"" + value + "\"}", token);
    }

    private static HttpResponse<String> put(SessionAuthenticationTest.Browser browser, String path,
            String body) throws Exception {
        return put(browser, path, body, browser.csrf());
    }

    private static HttpResponse<String> put(SessionAuthenticationTest.Browser browser, String path,
            String body, String token) throws Exception {
        return browser.request("PUT", path, body,
                "Content-Type", "application/json", "X-CSRF-TOKEN", token);
    }

    private static HttpResponse<String> post(SessionAuthenticationTest.Browser browser, String path,
            String body, String token) throws Exception {
        return browser.request("POST", path, body,
                "Content-Type", "application/json", "X-CSRF-TOKEN", token);
    }

    private static UUID report(UUID reporter, UUID assignee, String status, long version, String priority)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version,priority) VALUES(?,?,?,?,?,?,?,?)")) {
            insert.setObject(1, id); insert.setString(2, "Maintainer concurrency fixture");
            insert.setString(3, "Synthetic PostgreSQL race report"); insert.setObject(4, reporter);
            insert.setObject(5, assignee); insert.setString(6, status); insert.setLong(7, version);
            insert.setString(8, priority); insert.executeUpdate();
        }
        return id;
    }

    private static UUID proposal(UUID report, long version) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO resolution_proposals(id,report_id,status,version,contents) VALUES(?,?,'PENDING',?,?)")) {
            insert.setObject(1, id); insert.setObject(2, report); insert.setLong(3, version);
            insert.setString(4, "Pending resolution"); insert.executeUpdate();
        }
        return id;
    }

    private static void setCurrentProposal(UUID report, UUID proposal) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var update = c.prepareStatement(
                "UPDATE reports SET current_proposal_id=? WHERE id=?")) {
            update.setObject(1, proposal); update.setObject(2, report); assertEquals(1, update.executeUpdate());
        }
    }

    private static void checklist(UUID report, String[] draft, long revision, String[] shared) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?::text[],?,?::text[])")) {
            insert.setObject(1, report); insert.setString(2, pgArray(draft)); insert.setLong(3, revision);
            insert.setString(4, pgArray(shared)); insert.executeUpdate();
        }
    }

    private static String pgArray(String[] values) {
        return "{" + String.join(",", java.util.Arrays.stream(values)
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList()) + "}";
    }

    private static Connection lockReport(UUID report) throws SQLException {
        Connection c = SessionAuthenticationTest.connection();
        c.setAutoCommit(false);
        try (var lock = c.prepareStatement("SELECT id FROM reports WHERE id=? FOR UPDATE")) {
            lock.setObject(1, report);
            try (var row = lock.executeQuery()) { assertTrue(row.next()); }
        }
        return c;
    }

    private static Connection lockAccount(UUID account) throws SQLException {
        Connection c = SessionAuthenticationTest.connection();
        c.setAutoCommit(false);
        try (var lock = c.prepareStatement("SELECT id FROM app_accounts WHERE id=? FOR UPDATE")) {
            lock.setObject(1, account);
            try (var row = lock.executeQuery()) { assertTrue(row.next()); }
        }
        return c;
    }

    private static long blockerPid(Connection connection) throws SQLException {
        try (var query = connection.createStatement().executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(query.next());
            return query.getLong(1);
        }
    }

    private static List<Long> blockedPids(long blockerPid) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement("""
                WITH RECURSIVE waiting(pid, path) AS (
                    SELECT pid, ARRAY[pid] FROM pg_stat_activity
                    WHERE ? = ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock' AND state='active'
                    UNION ALL
                    SELECT activity.pid, waiting.path || activity.pid
                    FROM pg_stat_activity activity JOIN waiting
                      ON waiting.pid = ANY(pg_blocking_pids(activity.pid))
                    WHERE NOT activity.pid = ANY(waiting.path)
                      AND activity.wait_event_type='Lock' AND activity.state='active'
                ) SELECT DISTINCT pid FROM waiting
                """)) {
            query.setLong(1, blockerPid);
            try (var rows = query.executeQuery()) {
                List<Long> result = new ArrayList<>();
                while (rows.next()) result.add(rows.getLong(1));
                return result;
            }
        }
    }

    private static void awaitBlockedCount(long blockerPid, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (blockedPids(blockerPid).size() >= expected) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        fail("Expected " + expected + " PostgreSQL requests blocked by backend " + blockerPid);
    }

    private static long awaitBlockedPid(long blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            List<Long> blocked = blockedPids(blockerPid);
            if (!blocked.isEmpty()) return blocked.get(0);
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        fail("Mutation did not reach the report lock within the bounded wait");
        return -1;
    }

    private static void awaitBlockedBy(AtomicLong waiterPid, long blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            long pid = waiterPid.get();
            if (pid != 0 && blockedPids(blockerPid).contains(pid)) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        fail("Account deactivation did not wait for the in-flight Maintainer transaction");
    }

    private static void stopWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "race workers must terminate");
    }

    private static void deactivateAndCommit(Connection c, UUID account) throws SQLException {
        try (var update = c.prepareStatement("UPDATE app_accounts SET active=false WHERE id=?")) {
            update.setObject(1, account); assertEquals(1, update.executeUpdate());
        }
        c.commit();
    }

    private static void createHistoryFailureTrigger(String suffix, UUID report) throws SQLException {
        String function = suffix + "_fn";
        try (var c = SessionAuthenticationTest.connection(); var statement = c.createStatement()) {
            statement.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.report_id = '"
                    + report + "'::uuid THEN RAISE EXCEPTION 'injected history failure'; END IF; RETURN NEW; END $$");
            statement.execute("CREATE TRIGGER " + suffix + " BEFORE INSERT ON report_history FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        }
    }

    private static void dropHistoryFailureTrigger(String suffix) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var statement = c.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS " + suffix + " ON report_history");
            statement.execute("DROP FUNCTION IF EXISTS " + suffix + "_fn()");
        }
    }

    private static long reportVersion(UUID report) throws SQLException { return reportLong(report, "version"); }
    private static long draftRevision(UUID report) throws SQLException { return reportLong(report, "draft_revision"); }

    private static long reportLong(UUID report, String field) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT " + field + " FROM " + (field.equals("draft_revision") ? "investigation_checklists" : "reports") + " WHERE "
                        + (field.equals("draft_revision") ? "report_id" : "id") + "=?")) {
            query.setObject(1, report);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getLong(1); }
        }
    }

    private static String reportStatus(UUID report) throws SQLException { return reportText(report, "status"); }
    private static String reportPriority(UUID report) throws SQLException { return reportText(report, "priority"); }

    private static String reportText(UUID report, String column) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT " + column + " FROM reports WHERE id=?")) {
            query.setObject(1, report);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getString(1); }
        }
    }

    private static long historyCount(UUID report) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT count(*) FROM report_history WHERE report_id=?")) {
            query.setObject(1, report);
            try (var row = query.executeQuery()) { row.next(); return row.getLong(1); }
        }
    }

    private static String proposalStatus(UUID proposal) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT status FROM resolution_proposals WHERE id=?")) {
            query.setObject(1, proposal);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getString(1); }
        }
    }

    private static List<String> sharedSteps(UUID report) throws SQLException { return checklistColumn(report, "shared_steps"); }
    private static List<String> draftSteps(UUID report) throws SQLException { return checklistColumn(report, "draft_steps"); }

    private static List<String> checklistColumn(UUID report, String column) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT " + column + " FROM investigation_checklists WHERE report_id=?")) {
            query.setObject(1, report);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                return List.of((String[]) row.getArray(1).getArray());
            }
        }
    }

    private static boolean accountActive(UUID account) throws SQLException {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT active FROM app_accounts WHERE id=?")) {
            query.setObject(1, account);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getBoolean(1); }
        }
    }
}
