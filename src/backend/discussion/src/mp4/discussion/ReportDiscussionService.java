package mp4.discussion;

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

/**
 * One comment thread per report (report-discussion design D1, D2, D3). The caller passes a trusted actor
 * resolved by the authentication layer; this class decides whether that actor participates in the thread.
 * An empty result means "cannot see": the report is missing or the actor is not a participant, and each
 * role endpoint maps that to its own existing response.
 *
 * <p>Posting inserts exactly one {@code report_comments} row. It never touches {@code reports} (D3).
 * Display names are read from {@code public.app_accounts} at read time and never stored (D1).
 */
public final class ReportDiscussionService {
    /** The session actor. {@code role} is the account role: REPORTER, DEVELOPER or MAINTAINER. */
    public record Actor(UUID id, String role, boolean active) {}
    /** {@code displayName} is null only if the author's account row no longer exists. */
    public record Author(UUID id, String displayName, String role) {}
    public record Comment(UUID id, String body, Author author, Instant createdAt) {}
    public record Page(List<Comment> items, int page, int pageSize, long totalItems) {}

    private static final String AUTHOR_COLUMNS =
            "c.id, c.text, c.author_id, c.author_role, c.created_at, a.display_name";
    private static final String ACCOUNT_JOIN = " LEFT JOIN public.app_accounts a ON a.id = c.author_id";

    private final DataSource database;

    public ReportDiscussionService(DataSource database) {
        this.database = database;
    }

    /** Oldest first: created_at, then id. Empty when the actor cannot see the report. */
    public Optional<Page> list(Actor actor, UUID reportId, int page, int pageSize) throws SQLException {
        String participant = participantCondition(actor);
        if (participant == null) return Optional.empty();
        long offset = ((long) page - 1) * pageSize;
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                boolean visible;
                try (var query = connection.prepareStatement(
                        "SELECT 1 FROM reports r WHERE r.id = ? AND " + participant)) {
                    query.setObject(1, reportId);
                    query.setObject(2, actor.id());
                    try (var row = query.executeQuery()) {
                        visible = row.next();
                    }
                }
                if (!visible) {
                    connection.rollback();
                    return Optional.empty();
                }
                long total;
                try (var count = connection.prepareStatement(
                        "SELECT count(*) FROM report_comments WHERE report_id = ?")) {
                    count.setObject(1, reportId);
                    try (var row = count.executeQuery()) {
                        row.next();
                        total = row.getLong(1);
                    }
                }
                var items = new ArrayList<Comment>();
                try (var query = connection.prepareStatement("SELECT " + AUTHOR_COLUMNS
                        + " FROM report_comments c" + ACCOUNT_JOIN
                        + " WHERE c.report_id = ? ORDER BY c.created_at ASC, c.id ASC LIMIT ? OFFSET ?")) {
                    query.setObject(1, reportId);
                    query.setInt(2, pageSize);
                    query.setLong(3, offset);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) items.add(readComment(rows));
                    }
                }
                connection.commit();
                return Optional.of(new Page(List.copyOf(items), page, pageSize, total));
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection, failure);
                throw failure;
            }
        }
    }

    /**
     * Stores an already validated body as the actor's comment, with the actor's current role. The participant
     * check and the insert are one statement, so nothing is stored when the report cannot be seen.
     */
    public Optional<Comment> post(Actor actor, UUID reportId, String body) throws SQLException {
        String participant = participantCondition(actor);
        if (participant == null) return Optional.empty();
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                Optional<Comment> created;
                try (var write = connection.prepareStatement("WITH c AS ("
                        + " INSERT INTO report_comments (id, report_id, author_id, author_role, text)"
                        + " SELECT ?, r.id, ?, ?, ? FROM reports r WHERE r.id = ? AND " + participant
                        + " RETURNING id, text, author_id, author_role, created_at)"
                        + " SELECT " + AUTHOR_COLUMNS + " FROM c" + ACCOUNT_JOIN)) {
                    write.setObject(1, UUID.randomUUID());
                    write.setObject(2, actor.id());
                    write.setString(3, actor.role());
                    write.setString(4, body);
                    write.setObject(5, reportId);
                    write.setObject(6, actor.id());
                    try (var row = write.executeQuery()) {
                        created = row.next() ? Optional.of(readComment(row)) : Optional.empty();
                    }
                }
                connection.commit();
                return created;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection, failure);
                throw failure;
            }
        }
    }

    /**
     * SQL predicate on {@code reports r} with one placeholder for the actor id, or null when the actor can
     * never participate. Only the Reporter rule exists so far; Developer and Maintainer participation is
     * task 2.4 and, until then, fails closed.
     */
    private static String participantCondition(Actor actor) {
        if (actor == null || actor.id() == null || !actor.active() || actor.role() == null) return null;
        return switch (actor.role()) {
            case "REPORTER" -> "r.reporter_id = ?";
            default -> null;
        };
    }

    private static Comment readComment(ResultSet row) throws SQLException {
        return new Comment(row.getObject("id", UUID.class), row.getString("text"),
                new Author(row.getObject("author_id", UUID.class), row.getString("display_name"),
                        row.getString("author_role")),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static void rollbackQuietly(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
