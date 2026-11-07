package mp4.developer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Saves private drafts for actors resolved by the application's authentication layer. */
public final class ChecklistDraftStore {
    public enum Role { REPORTER, DEVELOPER, MAINTAINER }
    public enum Outcome { SAVED, FORBIDDEN, INVALID_STATE, STALE_VERSION }

    public record Actor(UUID id, Role role, boolean active) {}
    public record SaveResult(Outcome outcome, Long draftRevision) {}

    private final DataSource database;

    public ChecklistDraftStore(DataSource database) {
        this.database = database;
    }

    public SaveResult save(Actor actor, UUID reportId, long expectedDraftRevision, List<String> steps)
            throws SQLException {
        if (!actor.active() || actor.role() != Role.DEVELOPER) {
            return new SaveResult(Outcome.FORBIDDEN, null);
        }
        if (expectedDraftRevision < 0) {
            throw new IllegalArgumentException("Expected draft revision must be nonnegative");
        }
        List<String> draft = List.copyOf(steps);

        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                SaveResult result = saveUnderReportLock(connection, actor, reportId, expectedDraftRevision, draft);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private SaveResult saveUnderReportLock(Connection connection, Actor actor, UUID reportId,
            long expectedDraftRevision, List<String> steps) throws SQLException {
        try (var report = connection.prepareStatement(
                "SELECT assignee_id, status FROM reports WHERE id = ? FOR UPDATE")) {
            report.setObject(1, reportId);
            try (var row = report.executeQuery()) {
                if (!row.next() || !actor.id().equals(row.getObject("assignee_id", UUID.class))) {
                    return new SaveResult(Outcome.FORBIDDEN, null);
                }
                if (!List.of("ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED").contains(row.getString("status"))) {
                    return new SaveResult(Outcome.INVALID_STATE, null);
                }
            }
        }

        // Read the draft after acquiring the report lock so a waiting Save sees the last commit.
        long revision = 0;
        try (var checklist = connection.prepareStatement(
                "SELECT draft_revision FROM investigation_checklists WHERE report_id = ?")) {
            checklist.setObject(1, reportId);
            try (var row = checklist.executeQuery()) {
                if (row.next()) {
                    revision = row.getLong(1);
                }
            }
        }
        if (revision != expectedDraftRevision) {
            return new SaveResult(Outcome.STALE_VERSION, null);
        }

        long nextRevision = Math.addExact(revision, 1);
        var draftSteps = connection.createArrayOf("text", steps.toArray(String[]::new));
        try (var write = connection.prepareStatement("""
                INSERT INTO investigation_checklists (report_id, draft_steps, draft_revision)
                VALUES (?, ?, ?)
                ON CONFLICT (report_id) DO UPDATE
                    SET draft_steps = EXCLUDED.draft_steps,
                        draft_revision = EXCLUDED.draft_revision
                """)) {
            write.setObject(1, reportId);
            write.setArray(2, draftSteps);
            write.setLong(3, nextRevision);
            write.executeUpdate();
        } finally {
            draftSteps.free();
        }
        return new SaveResult(Outcome.SAVED, nextRevision);
    }
}
