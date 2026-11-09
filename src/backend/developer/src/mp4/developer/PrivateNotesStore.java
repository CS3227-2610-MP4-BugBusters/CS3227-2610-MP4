package mp4.developer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Stores one private, revisioned investigation note for each report. */
public final class PrivateNotesStore {
    public static final long MAX_SAFE_REVISION = 9_007_199_254_740_991L;

    public enum Outcome { SAVED, AUTHORIZED, FORBIDDEN, UNAVAILABLE, INVALID_STATE, STALE_VERSION, VERSION_LIMIT }
    public record Actor(UUID id, String role, long generation) {}
    public record Notes(String text, long revision) {}
    public record ReadResult(Outcome outcome, Notes notes) {}
    public record SaveResult(Outcome outcome, long revision) {}

    private final DataSource database;

    public PrivateNotesStore(DataSource database) { this.database = database; }

    public ReadResult read(Actor actor, UUID reportId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                if (!activeDeveloper(connection, actor)) {
                    connection.commit();
                    return new ReadResult(Outcome.FORBIDDEN, null);
                }
                try (var report = connection.prepareStatement(
                        "SELECT id FROM reports WHERE id = ? AND assignee_id = ? FOR UPDATE")) {
                    report.setObject(1, reportId);
                    report.setObject(2, actor.id());
                    try (var row = report.executeQuery()) {
                        if (!row.next()) { connection.commit(); return new ReadResult(Outcome.UNAVAILABLE, null); }
                    }
                }
                Notes notes = new Notes("", 0);
                try (var query = connection.prepareStatement(
                        "SELECT text, revision FROM developer_private_notes WHERE report_id = ?")) {
                    query.setObject(1, reportId);
                    try (var row = query.executeQuery()) {
                        if (row.next()) notes = new Notes(row.getString("text"), row.getLong("revision"));
                    }
                }
                connection.commit();
                return new ReadResult(Outcome.AUTHORIZED, notes);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    public SaveResult save(Actor actor, UUID reportId, long expectedRevision, String text) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                if (!activeDeveloper(connection, actor)) {
                    connection.commit();
                    return new SaveResult(Outcome.FORBIDDEN, 0);
                }
                String status;
                try (var report = connection.prepareStatement(
                        "SELECT status FROM reports WHERE id = ? AND assignee_id = ? FOR UPDATE")) {
                    report.setObject(1, reportId);
                    report.setObject(2, actor.id());
                    try (var row = report.executeQuery()) {
                        if (!row.next()) { connection.commit(); return new SaveResult(Outcome.UNAVAILABLE, 0); }
                        status = row.getString("status");
                    }
                }
                if (!List.of("ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED").contains(status)) {
                    connection.commit();
                    return new SaveResult(Outcome.INVALID_STATE, 0);
                }
                long revision = 0;
                try (var query = connection.prepareStatement(
                        "SELECT revision FROM developer_private_notes WHERE report_id = ?")) {
                    query.setObject(1, reportId);
                    try (var row = query.executeQuery()) { if (row.next()) revision = row.getLong(1); }
                }
                if (revision != expectedRevision) {
                    connection.commit();
                    return new SaveResult(Outcome.STALE_VERSION, revision);
                }
                if (revision >= MAX_SAFE_REVISION) {
                    connection.commit();
                    return new SaveResult(Outcome.VERSION_LIMIT, revision);
                }
                long nextRevision = Math.addExact(revision, 1);
                try (var write = connection.prepareStatement("""
                        INSERT INTO developer_private_notes (report_id, text, revision) VALUES (?, ?, ?)
                        ON CONFLICT (report_id) DO UPDATE SET text = EXCLUDED.text, revision = EXCLUDED.revision
                        """)) {
                    write.setObject(1, reportId);
                    write.setString(2, text);
                    write.setLong(3, nextRevision);
                    write.executeUpdate();
                }
                connection.commit();
                return new SaveResult(Outcome.SAVED, nextRevision);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    private static boolean activeDeveloper(Connection connection, Actor actor) throws SQLException {
        try (var account = connection.prepareStatement("""
                SELECT role, active, auth_generation FROM app_accounts WHERE id = ? FOR UPDATE
                """)) {
            account.setObject(1, actor.id());
            try (var row = account.executeQuery()) {
                return row.next() && row.getBoolean("active") && "DEVELOPER".equals(row.getString("role"))
                        && row.getLong("auth_generation") == actor.generation();
            }
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); }
        catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }
}
