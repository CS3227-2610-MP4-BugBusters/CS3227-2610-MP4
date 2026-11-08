package mp4.developer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import mp4.developer.ChecklistDraftStore.Actor;

/** PostgreSQL-backed private notes store. The report row serializes writes with reassignment/closure. */
public final class JdbcPrivateNotesService implements PrivateNotesService {
    private static final long MAX_SAFE_REVISION = 9_007_199_254_740_991L;
    private static final List<String> EDITABLE_STATES = List.of("ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED");

    private final DataSource database;

    public JdbcPrivateNotesService(DataSource database) {
        this.database = database;
    }

    @Override
    public Note read(Actor actor, UUID reportId) throws SQLException, AccessDeniedException {
        requireDeveloper(actor);
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                try (var report = connection.prepareStatement(
                        "SELECT assignee_id FROM reports WHERE id = ? FOR SHARE")) {
                    report.setObject(1, reportId);
                    try (var row = report.executeQuery()) {
                        if (!row.next() || !actor.id().equals(row.getObject("assignee_id", UUID.class))) {
                            throw new AccessDeniedException();
                        }
                    }
                }
                Note note;
                try (var notes = connection.prepareStatement(
                        "SELECT body, notes_revision FROM developer_private_notes WHERE report_id = ?")) {
                    notes.setObject(1, reportId);
                    try (var row = notes.executeQuery()) {
                        note = row.next() ? new Note(row.getString("body"), row.getLong("notes_revision"))
                                : new Note("", 0);
                    }
                }
                connection.commit();
                return note;
            } catch (SQLException | RuntimeException | AccessDeniedException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    @Override
    public SaveResult replace(Actor actor, UUID reportId, String text, long expectedNotesRevision)
            throws SQLException, AccessDeniedException, InvalidStateException, StaleRevisionException,
            RevisionLimitException {
        requireDeveloper(actor);
        if (expectedNotesRevision < 0 || expectedNotesRevision > MAX_SAFE_REVISION) {
            throw new IllegalArgumentException("Invalid notes revision");
        }
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                lockAndCheckReport(connection, actor, reportId);
                long currentRevision = 0;
                try (var notes = connection.prepareStatement(
                        "SELECT notes_revision FROM developer_private_notes WHERE report_id = ?")) {
                    notes.setObject(1, reportId);
                    try (var row = notes.executeQuery()) {
                        if (row.next()) currentRevision = row.getLong(1);
                    }
                }
                if (currentRevision != expectedNotesRevision) throw new StaleRevisionException();
                if (currentRevision == MAX_SAFE_REVISION) throw new RevisionLimitException();
                long nextRevision = currentRevision + 1;
                try (var write = connection.prepareStatement("""
                        INSERT INTO developer_private_notes (report_id, body, notes_revision)
                        VALUES (?, ?, ?)
                        ON CONFLICT (report_id) DO UPDATE
                            SET body = EXCLUDED.body, notes_revision = EXCLUDED.notes_revision
                        """)) {
                    write.setObject(1, reportId);
                    write.setString(2, text);
                    write.setLong(3, nextRevision);
                    write.executeUpdate();
                }
                connection.commit();
                return new SaveResult(nextRevision);
            } catch (SQLException | RuntimeException | AccessDeniedException | InvalidStateException
                    | StaleRevisionException | RevisionLimitException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private static void lockAndCheckReport(Connection connection, Actor actor, UUID reportId)
            throws SQLException, AccessDeniedException, InvalidStateException {
        try (var report = connection.prepareStatement(
                "SELECT assignee_id, status FROM reports WHERE id = ? FOR UPDATE")) {
            report.setObject(1, reportId);
            try (var row = report.executeQuery()) {
                if (!row.next() || !actor.id().equals(row.getObject("assignee_id", UUID.class))) {
                    throw new AccessDeniedException();
                }
                if (!EDITABLE_STATES.contains(row.getString("status"))) throw new InvalidStateException();
            }
        }
    }

    private static void requireDeveloper(Actor actor) throws AccessDeniedException {
        if (actor == null || !actor.active() || actor.role() != ChecklistDraftStore.Role.DEVELOPER
                || actor.id() == null) throw new AccessDeniedException();
    }
}
