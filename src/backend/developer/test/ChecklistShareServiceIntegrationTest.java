package mp4.developer;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

import static mp4.developer.ChecklistDraftStore.Role;
import static mp4.developer.ChecklistShareService.Outcome;

/** Focused PostgreSQL contract test for the ChecklistShareService boundary. */
public final class ChecklistShareServiceIntegrationTest {
    private static final UUID REPORT = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID REPORTER = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID DEVELOPER = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID PROPOSAL = UUID.fromString("00000000-0000-0000-0000-000000000105");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <jdbc-url> <service-class>");
        var db = new PGSimpleDataSource();
        db.setURL(args[0]);
        String password = System.getenv("MP4_TEST_DB_PASSWORD");
        if (password != null) db.setPassword(password);
        Class<?> implementation = Class.forName(args[1]);
        ChecklistShareService service;
        try {
            service = (ChecklistShareService) implementation
                    .getConstructor(javax.sql.DataSource.class).newInstance(db);
        } catch (NoSuchMethodException noDataSourceConstructor) {
            service = (ChecklistShareService) implementation.getConstructor().newInstance();
        }
        var assigned = new ChecklistDraftStore.Actor(DEVELOPER, Role.DEVELOPER, true);

        seed(db, List.of("New published step"), 4, List.of("Old published step"), 7,
                "RESOLUTION_PROPOSED", "PENDING");
        var changed = service.share(new ChecklistShareService.ShareRequest(assigned, REPORT, 4, 7));
        expect(Outcome.SHARED, changed.outcome());
        expect(8L, changed.reportVersion());
        verify(db, List.of("New published step"), 4, List.of("New published step"), 8,
                "IN_PROGRESS", "SUPERSEDED", PROPOSAL);

        seed(db, List.of(), 5, List.of("Previously published"), 9, "IN_PROGRESS", "PENDING");
        var empty = service.share(new ChecklistShareService.ShareRequest(assigned, REPORT, 5, 9));
        expect(Outcome.EMPTY_DRAFT, empty.outcome());
        verify(db, List.of(), 5, List.of("Previously published"), 9,
                "IN_PROGRESS", "PENDING", PROPOSAL);

        seed(db, List.of("Same steps"), 6, List.of("Same steps"), 11,
                "RESOLUTION_PROPOSED", "PENDING");
        var unchanged = service.share(new ChecklistShareService.ShareRequest(assigned, REPORT, 6, 11));
        expect(Outcome.UNCHANGED, unchanged.outcome());
        verify(db, List.of("Same steps"), 6, List.of("Same steps"), 11,
                "RESOLUTION_PROPOSED", "PENDING", PROPOSAL);

        seed(db, List.of("Draft"), 3, List.of("Published"), 12, "IN_PROGRESS", "PENDING");
        expect(Outcome.STALE_VERSION,
                service.share(new ChecklistShareService.ShareRequest(assigned, REPORT, 2, 12)).outcome());
        verify(db, List.of("Draft"), 3, List.of("Published"), 12,
                "IN_PROGRESS", "PENDING", PROPOSAL);
        expect(Outcome.STALE_VERSION,
                service.share(new ChecklistShareService.ShareRequest(assigned, REPORT, 3, 11)).outcome());
        verify(db, List.of("Draft"), 3, List.of("Published"), 12,
                "IN_PROGRESS", "PENDING", PROPOSAL);

        var unrelated = new ChecklistDraftStore.Actor(OTHER, Role.DEVELOPER, true);
        expect(Outcome.FORBIDDEN,
                service.share(new ChecklistShareService.ShareRequest(unrelated, REPORT, 3, 12)).outcome());
        verify(db, List.of("Draft"), 3, List.of("Published"), 12,
                "IN_PROGRESS", "PENDING", PROPOSAL);
        System.out.println("PostgreSQL Share service checks passed: changed review Share, empty preservation, unchanged no-op, stale draft/report versions, and authorization denial.");
    }

    private static void seed(PGSimpleDataSource db, List<String> draft, long revision, List<String> shared,
            long reportVersion, String reportStatus, String proposalStatus) throws SQLException {
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("TRUNCATE investigation_checklists, reports, resolution_proposals CASCADE");
        }
        update(db, "INSERT INTO resolution_proposals VALUES (?, ?, ?, 2, 'review text')",
                PROPOSAL, REPORT, proposalStatus);
        update(db, "INSERT INTO reports VALUES (?, ?, ?, ?, ?, ?)",
                REPORT, REPORTER, DEVELOPER, reportStatus, reportVersion, PROPOSAL);
        try (var c = db.getConnection()) {
            var draftArray = c.createArrayOf("text", draft.toArray(String[]::new));
            var sharedArray = c.createArrayOf("text", shared.toArray(String[]::new));
            try (var s = c.prepareStatement("INSERT INTO investigation_checklists VALUES (?, ?, ?, ?)")) {
                s.setObject(1, REPORT);
                s.setArray(2, draftArray);
                s.setLong(3, revision);
                s.setArray(4, sharedArray);
                s.executeUpdate();
            } finally {
                draftArray.free();
                sharedArray.free();
            }
        }
    }

    private static void verify(PGSimpleDataSource db, List<String> draft, long revision, List<String> shared,
            long reportVersion, String reportStatus, String proposalStatus, UUID proposalId) throws SQLException {
        try (var c = db.getConnection(); var s = c.prepareStatement("""
                SELECT draft_steps, draft_revision, shared_steps, reports.version, reports.status,
                       reports.current_proposal_id, resolution_proposals.status AS proposal_status
                FROM reports JOIN investigation_checklists ON reports.id = report_id
                JOIN resolution_proposals ON resolution_proposals.id = reports.current_proposal_id
                WHERE reports.id = ?
                """)) {
            s.setObject(1, REPORT);
            try (var row = s.executeQuery()) {
                expect(true, row.next());
                expect(draft, List.of((String[]) row.getArray("draft_steps").getArray()));
                expect(revision, row.getLong("draft_revision"));
                expect(shared, List.of((String[]) row.getArray("shared_steps").getArray()));
                expect(reportVersion, row.getLong("version"));
                expect(reportStatus, row.getString("status"));
                expect(proposalId, row.getObject("current_proposal_id", UUID.class));
                expect(proposalStatus, row.getString("proposal_status"));
            }
        }
    }

    private static void update(PGSimpleDataSource db, String sql, Object... values) throws SQLException {
        try (var c = db.getConnection(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            expect(1, s.executeUpdate());
        }
    }

    private static void expect(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
