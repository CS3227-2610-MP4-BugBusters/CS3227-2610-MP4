package mp4.developer;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.postgresql.ds.PGSimpleDataSource;

import static mp4.developer.ChecklistDraftStore.Outcome;
import static mp4.developer.ChecklistDraftStore.Role;

public final class ChecklistDraftStoreIntegrationTest {
    private static final UUID REPORT = UUID.randomUUID();
    private static final UUID D1 = UUID.randomUUID();
    private static final UUID D2 = UUID.randomUUID();
    private static final UUID REPORTER = UUID.randomUUID();
    private static final UUID PROPOSAL = UUID.randomUUID();

    public static void main(String[] args) throws Exception {
        var db = new PGSimpleDataSource();
        db.setURL(args[0]);
        String password = System.getenv("MP4_TEST_DB_PASSWORD");
        if (password != null) {
            db.setPassword(password);
        }
        var store = new ChecklistDraftStore(db);
        var developer = new ChecklistDraftStore.Actor(D1, Role.DEVELOPER, true);

        update(db, "INSERT INTO reports VALUES (?, ?, ?, 'RESOLUTION_PROPOSED', 7, ?)",
                REPORT, REPORTER, D1, PROPOSAL);
        var first = store.save(developer, REPORT, 0, List.of("First draft"));
        expect(Outcome.SAVED, first.outcome());
        expect(1L, first.draftRevision());
        verifyState(db, "First draft", 1, List.of());

        update(db, "UPDATE investigation_checklists SET shared_steps = ARRAY['Published step'] WHERE report_id = ?",
                REPORT);
        expect(Outcome.STALE_VERSION, store.save(developer, REPORT, 0, List.of("Stale draft")).outcome());
        verifyState(db, "First draft", 1, List.of("Published step"));

        String winningDraft = competingSaves(store, developer);
        verifyState(db, winningDraft, 2, List.of("Published step"));

        update(db, "UPDATE reports SET assignee_id = ? WHERE id = ?", D2, REPORT);
        expect(Outcome.FORBIDDEN, store.save(developer, REPORT, 2, List.of("Former assignee")).outcome());
        verifyState(db, winningDraft, 2, List.of("Published step"));

        var currentDeveloper = new ChecklistDraftStore.Actor(D2, Role.DEVELOPER, true);
        var reporter = new ChecklistDraftStore.Actor(REPORTER, Role.REPORTER, true);
        expect(Outcome.FORBIDDEN, store.save(reporter, REPORT, 2, List.of("Reporter write")).outcome());
        verifyState(db, winningDraft, 2, List.of("Published step"));

        update(db, "UPDATE reports SET status = 'CLOSED' WHERE id = ?", REPORT);
        expect(Outcome.INVALID_STATE, store.save(currentDeveloper, REPORT, 2, List.of("Closed edit")).outcome());
        System.out.println("PostgreSQL checks passed: first Save, stale revision, competing saves, former assignee, Reporter write, and Closed report.");
    }

    private static String competingSaves(ChecklistDraftStore store, ChecklistDraftStore.Actor developer)
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> {
                start.await();
                return store.save(developer, REPORT, 1, List.of("Draft A"));
            });
            var b = pool.submit(() -> {
                start.await();
                return store.save(developer, REPORT, 1, List.of("Draft B"));
            });
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            if (first.outcome() == Outcome.SAVED) {
                expect(2L, first.draftRevision());
                expect(Outcome.STALE_VERSION, second.outcome());
                return "Draft A";
            }
            expect(Outcome.STALE_VERSION, first.outcome());
            expect(Outcome.SAVED, second.outcome());
            expect(2L, second.draftRevision());
            return "Draft B";
        } finally {
            pool.shutdownNow();
        }
    }

    private static void verifyState(PGSimpleDataSource db, String draft, long revision, List<String> shared)
            throws SQLException {
        try (var connection = db.getConnection();
                var query = connection.prepareStatement("""
                        SELECT draft_steps, draft_revision, shared_steps, version, status, current_proposal_id
                        FROM investigation_checklists JOIN reports ON reports.id = report_id
                        WHERE report_id = ?
                        """)) {
            query.setObject(1, REPORT);
            try (var row = query.executeQuery()) {
                expect(true, row.next());
                expect(List.of(draft), List.of((String[]) row.getArray("draft_steps").getArray()));
                expect(revision, row.getLong("draft_revision"));
                expect(shared, List.of((String[]) row.getArray("shared_steps").getArray()));
                expect(7L, row.getLong("version"));
                expect("RESOLUTION_PROPOSED", row.getString("status"));
                expect(PROPOSAL, row.getObject("current_proposal_id", UUID.class));
            }
        }
    }

    private static void update(PGSimpleDataSource db, String sql, Object... values) throws SQLException {
        try (var connection = db.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            expect(1, statement.executeUpdate());
        }
    }

    private static void expect(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
