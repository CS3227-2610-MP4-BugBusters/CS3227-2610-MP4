package mp4.reporter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Creates, reads and edits reports owned by a Reporter resolved by the authentication layer. */
public final class ReporterReportStore {
    public record Report(UUID id, String title, String description, String status, long version,
            Instant createdAt, Instant updatedAt) {}
    public record Summary(UUID id, String title, String status, Instant createdAt, Instant updatedAt) {}
    public record Page(List<Summary> items, int page, int pageSize, long totalItems) {}

    private final DataSource database;

    public ReporterReportStore(DataSource database) {
        this.database = database;
    }

    /** Thrown when the Reporter already has {@value #CREATES_PER_WINDOW} creates in the rolling window. */
    public static final class RateLimited extends Exception {
        private final long retryAfterSeconds;

        RateLimited(long retryAfterSeconds) {
            super(null, null, false, false);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        /** Whole seconds, 1..60, until the oldest counted create leaves the window. */
        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** Thrown when an edit matched no row; nothing was changed (design D17). */
    public static final class Refused extends Exception {
        public enum Reason { NOT_FOUND, LOCKED, VERSION_CONFLICT }

        private final Reason reason;

        Refused(Reason reason) {
            super(null, null, false, false);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private static final int CREATES_PER_WINDOW = 3;

    /**
     * Inserts an already validated report. Both timestamps come from the transaction's {@code now()}.
     * RPT-08 (design D10): the per-Reporter advisory lock serialises the window check and the insert.
     */
    public Report create(UUID reporterId, String title, String description) throws SQLException, RateLimited {
        UUID id = UUID.randomUUID();
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                lockReporter(connection, reporterId);
                long retryAfter = retryAfterIfLimited(connection, reporterId);
                if (retryAfter > 0) {
                    connection.rollback();
                    throw new RateLimited(retryAfter);
                }
                Report report = insert(connection, id, reporterId, title, description);
                connection.commit();
                return report;
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

    /** Returns the report only when reporterId filed it; another Reporter's report looks missing. */
    public Optional<Report> find(UUID reporterId, UUID reportId) throws SQLException {
        try (Connection connection = database.getConnection();
                var query = connection.prepareStatement("""
                        SELECT id, title, description, status, version, created_at, updated_at
                        FROM reports WHERE id = ? AND reporter_id = ?
                        """)) {
            query.setObject(1, reportId);
            query.setObject(2, reporterId);
            try (var row = query.executeQuery()) {
                return row.next() ? Optional.of(readReport(row)) : Optional.empty();
            }
        }
    }

    /** RPT-09: replaces both fields of the Reporter's unassigned SUBMITTED report at the given version. */
    public Report edit(UUID reporterId, UUID reportId, long version, String title, String description)
            throws SQLException, Refused {
        return updateUnlocked(reporterId, reportId, version, "title = ?, description = ?", title, description);
    }

    public Page list(UUID reporterId, int page, int pageSize) throws SQLException {
        long offset = ((long) page - 1) * pageSize;
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                long total;
                try (var count = connection.prepareStatement("SELECT count(*) FROM reports WHERE reporter_id = ?")) {
                    count.setObject(1, reporterId);
                    try (var row = count.executeQuery()) {
                        row.next();
                        total = row.getLong(1);
                    }
                }
                var items = new ArrayList<Summary>();
                try (var query = connection.prepareStatement("""
                        SELECT id, title, status, created_at, updated_at
                        FROM reports WHERE reporter_id = ?
                        ORDER BY created_at DESC, id ASC
                        LIMIT ? OFFSET ?
                        """)) {
                    query.setObject(1, reporterId);
                    query.setInt(2, pageSize);
                    query.setLong(3, offset);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            items.add(new Summary(rows.getObject("id", UUID.class), rows.getString("title"),
                                    rows.getString("status"), instant(rows, "created_at"),
                                    instant(rows, "updated_at")));
                        }
                    }
                }
                connection.commit();
                return new Page(List.copyOf(items), page, pageSize, total);
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

    /**
     * D17: one conditional UPDATE, so a concurrent assignment either commits first (0 rows) or waits on the
     * row lock. {@code assignments} is a fixed SQL fragment from this class whose placeholders take
     * {@code values}. On 0 rows the same READ COMMITTED transaction reads the row to classify the refusal.
     */
    private Report updateUnlocked(UUID reporterId, UUID reportId, long version, String assignments,
            String... values) throws SQLException, Refused {
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                Optional<Report> updated;
                try (var write = connection.prepareStatement("UPDATE reports SET " + assignments + """
                        , version = version + 1, updated_at = now()
                        WHERE id = ? AND reporter_id = ? AND assignee_id IS NULL AND status = 'SUBMITTED'
                          AND version = ?
                        RETURNING id, title, description, status, version, created_at, updated_at
                        """)) {
                    int index = 1;
                    for (String value : values) write.setString(index++, value);
                    write.setObject(index++, reportId);
                    write.setObject(index++, reporterId);
                    write.setLong(index, version);
                    try (var row = write.executeQuery()) {
                        updated = row.next() ? Optional.of(readReport(row)) : Optional.empty();
                    }
                }
                if (updated.isPresent()) {
                    connection.commit();
                    return updated.get();
                }
                Refused.Reason reason = refusalReason(connection, reporterId, reportId);
                connection.rollback();
                throw new Refused(reason);
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

    /** Another Reporter's report is NOT_FOUND, never LOCKED or VERSION_CONFLICT. */
    private static Refused.Reason refusalReason(Connection connection, UUID reporterId, UUID reportId)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT assignee_id IS NOT NULL OR status <> 'SUBMITTED'
                FROM reports WHERE id = ? AND reporter_id = ?
                """)) {
            query.setObject(1, reportId);
            query.setObject(2, reporterId);
            try (var row = query.executeQuery()) {
                if (!row.next()) return Refused.Reason.NOT_FOUND;
                return row.getBoolean(1) ? Refused.Reason.LOCKED : Refused.Reason.VERSION_CONFLICT;
            }
        }
    }

    /** Transaction-scoped lock keyed on a 64-bit fold of the id; a rare collision only adds serialisation. */
    private static void lockReporter(Connection connection, UUID reporterId) throws SQLException {
        try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            lock.setLong(1, reporterId.getMostSignificantBits() ^ reporterId.getLeastSignificantBits());
            try (var row = lock.executeQuery()) {
                row.next();
            }
        }
    }

    /** Returns 0 when a create is allowed, otherwise the Retry-After seconds, all on the database clock. */
    private static long retryAfterIfLimited(Connection connection, UUID reporterId) throws SQLException {
        try (var window = connection.prepareStatement("""
                SELECT count(*),
                       least(60, greatest(1, ceil(extract(epoch FROM
                           min(created_at) + interval '60 seconds' - now()))))::bigint
                FROM reports WHERE reporter_id = ? AND created_at > now() - interval '60 seconds'
                """)) {
            window.setObject(1, reporterId);
            try (var row = window.executeQuery()) {
                row.next();
                return row.getLong(1) >= CREATES_PER_WINDOW ? row.getLong(2) : 0;
            }
        }
    }

    private static Report insert(Connection connection, UUID id, UUID reporterId, String title, String description)
            throws SQLException {
        try (var write = connection.prepareStatement("""
                INSERT INTO reports (id, title, description, reporter_id, assignee_id, status, version,
                                     created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, 'SUBMITTED', 0, now(), now())
                RETURNING id, title, description, status, version, created_at, updated_at
                """)) {
            write.setObject(1, id);
            write.setString(2, title);
            write.setString(3, description);
            write.setObject(4, reporterId);
            try (var row = write.executeQuery()) {
                row.next();
                return readReport(row);
            }
        }
    }

    private static Report readReport(ResultSet row) throws SQLException {
        return new Report(row.getObject("id", UUID.class), row.getString("title"), row.getString("description"),
                row.getString("status"), row.getLong("version"), instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }
}
