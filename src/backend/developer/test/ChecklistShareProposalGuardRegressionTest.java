package mp4.developer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

import static mp4.developer.ChecklistDraftStore.Role;
import static mp4.developer.ChecklistShareService.Outcome;

/** Regression checks that Share cannot mutate a proposal outside its pending report proposal. */
public final class ChecklistShareProposalGuardRegressionTest {
    private static final UUID REPORT_A = id("201");
    private static final UUID REPORT_B = id("202");
    private static final UUID DEVELOPER = id("203");
    private static final UUID REPORTER = id("204");
    private static final UUID PROPOSAL_A = id("205");
    private static final UUID PROPOSAL_B = id("206");

    public static void main(String[] args) throws Exception {
        var db = new PGSimpleDataSource();
        db.setURL(args[0]);
        String password = System.getenv("MP4_TEST_DB_PASSWORD");
        if (password != null) db.setPassword(password);
        var service = new JdbcChecklistShareService(db);
        var actor = new ChecklistDraftStore.Actor(DEVELOPER, Role.DEVELOPER, true);
        var failures = new ArrayList<String>();

        // Report A points at report B's proposal. That proposal belongs to a closed report.
        seed(db, true, "CLOSED");
        assertRejectedWithoutMutation("proposal belongs to another report", db, service, actor, failures);

        // The proposal belongs to A, but is already terminal instead of pending.
        seed(db, false, "CLOSED");
        assertRejectedWithoutMutation("same-report proposal is terminal", db, service, actor, failures);

        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("; ", failures));
        }
        System.out.println("Proposal guard regressions passed: mismatched report ownership and terminal proposal status were rejected without mutation.");
    }

    private static void assertRejectedWithoutMutation(String scenario, PGSimpleDataSource db,
            JdbcChecklistShareService service, ChecklistDraftStore.Actor actor, List<String> failures)
            throws SQLException {
        String before = snapshot(db);
        try {
            var result = service.share(new ChecklistShareService.ShareRequest(actor, REPORT_A, 4, 7));
            if (result.outcome() == Outcome.SHARED) {
                failures.add(scenario + " was reported SHARED");
            }
        } catch (SQLException rejected) {
            // A database rejection is acceptable if the transaction rolls back completely.
        }
        String after = snapshot(db);
        if (!before.equals(after)) failures.add(scenario + " mutated persisted state");
    }

    private static void seed(PGSimpleDataSource db, boolean mismatchedProposal, String proposalStatus)
            throws SQLException {
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("TRUNCATE investigation_checklists, reports, resolution_proposals CASCADE");
        }
        UUID proposalForA = mismatchedProposal ? PROPOSAL_B : PROPOSAL_A;
        if (mismatchedProposal) {
            insertProposal(db, PROPOSAL_B, REPORT_B, "CLOSED");
            insertReport(db, REPORT_B, PROPOSAL_B, "CLOSED", 3);
        } else {
            insertProposal(db, PROPOSAL_A, REPORT_A, proposalStatus);
        }
        insertReport(db, REPORT_A, proposalForA, "RESOLUTION_PROPOSED", 7);
        try (var c = db.getConnection()) {
            var draft = c.createArrayOf("text", new String[] {"new draft"});
            var shared = c.createArrayOf("text", new String[] {"old publication"});
            try (var s = c.prepareStatement("INSERT INTO investigation_checklists VALUES (?, ?, 4, ?)")) {
                s.setObject(1, REPORT_A);
                s.setArray(2, draft);
                s.setArray(3, shared);
                s.executeUpdate();
            } finally {
                draft.free();
                shared.free();
            }
        }
    }

    private static void insertProposal(PGSimpleDataSource db, UUID proposal, UUID report, String status)
            throws SQLException {
        update(db, "INSERT INTO resolution_proposals VALUES (?, ?, ?, 2, 'existing resolution')",
                proposal, report, status);
    }

    private static void insertReport(PGSimpleDataSource db, UUID report, UUID proposal, String status, long version)
            throws SQLException {
        update(db, "INSERT INTO reports VALUES (?, ?, ?, ?, ?, ?)",
                report, REPORTER, DEVELOPER, status, version, proposal);
    }

    private static String snapshot(PGSimpleDataSource db) throws SQLException {
        StringBuilder result = new StringBuilder();
        try (var c = db.getConnection(); var s = c.createStatement();
                var rows = s.executeQuery("""
                        SELECT 'report' AS kind, id AS record_id, status, version,
                               current_proposal_id::text AS related_id, NULL::text AS steps
                        FROM reports
                        UNION ALL
                        SELECT 'proposal', id, status, version, report_id::text, contents
                        FROM resolution_proposals
                        ORDER BY kind, record_id
                        """)) {
            while (rows.next()) result.append(rows.getString(1)).append('|').append(rows.getString(2))
                    .append('|').append(rows.getString(3)).append('|').append(rows.getLong(4))
                    .append('|').append(rows.getString(5)).append('|').append(rows.getString(6)).append('\n');
        }
        try (var c = db.getConnection(); var s = c.createStatement();
                var rows = s.executeQuery("SELECT report_id, draft_steps, draft_revision, shared_steps FROM investigation_checklists")) {
            while (rows.next()) result.append("checklist|").append(rows.getString(1)).append('|')
                    .append(List.of((String[]) rows.getArray(2).getArray())).append('|').append(rows.getLong(3))
                    .append('|').append(List.of((String[]) rows.getArray(4).getArray())).append('\n');
        }
        return result.toString();
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
}
