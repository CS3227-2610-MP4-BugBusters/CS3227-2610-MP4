package mp4.maintainer.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import mp4.maintainer.summary.SummaryContracts.*;
import mp4.maintainer.workflow.MaintainerContracts.Priority;
import mp4.maintainer.workflow.MaintainerContracts.Status;

/** Consistent source-only snapshots; the connection closes before any provider work. */
public final class JdbcQueueSnapshotReader implements SnapshotReader {
    private final DataSource database;
    private final Clock clock;
    private final int maximumContextReports;

    public JdbcQueueSnapshotReader(DataSource database, Clock clock, int maximumContextReports) {
        this.database = database;
        this.clock = clock;
        if (maximumContextReports < 1) throw new IllegalArgumentException("Context report limit must be positive");
        this.maximumContextReports = maximumContextReports;
    }

    @Override public Snapshot read(Actor actor, Filters filters, int maximumMembers) {
        try (Connection connection = database.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                try (var limits = connection.createStatement()) {
                    limits.execute("SET LOCAL statement_timeout='5s'");
                    limits.execute("SET LOCAL lock_timeout='5s'");
                }
                try (var account = connection.prepareStatement(
                        "SELECT role,active,auth_generation FROM public.app_accounts WHERE id=?")) {
                    account.setObject(1, actor.accountId());
                    try (var row = account.executeQuery()) {
                        if (!row.next() || !row.getBoolean("active") || !"MAINTAINER".equals(row.getString("role"))
                                || actor.generation() != row.getLong("auth_generation"))
                            throw new SummaryFailure(FailureCode.FORBIDDEN);
                    }
                }
                var capturedAt = clock.instant();
                List<ReportInput> reports = new ArrayList<>();
                try (var query = connection.prepareStatement("""
                        WITH selected AS (
                            SELECT id,title,description,status,priority,assignee_id,version,created_at,updated_at,
                                reproduction_steps,expected_behavior,actual_behavior,environment,
                                row_number() OVER (ORDER BY CASE priority
                                WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                                updated_at,id) AS context_rank
                            FROM public.reports
                            WHERE status<>'CLOSED' AND (?::text IS NULL OR status=?)
                              AND (?::text IS NULL OR priority=?) AND (?::uuid IS NULL OR assignee_id=?)
                            ORDER BY id LIMIT ?
                        )
                        SELECT id,title,status,priority,assignee_id,version,created_at,updated_at,context_rank,
                            CASE WHEN context_rank<=? THEN description ELSE '' END AS description,
                            CASE WHEN context_rank<=? THEN reproduction_steps ELSE '' END AS reproduction_steps,
                            CASE WHEN context_rank<=? THEN expected_behavior ELSE '' END AS expected_behavior,
                            CASE WHEN context_rank<=? THEN actual_behavior ELSE '' END AS actual_behavior,
                            CASE WHEN context_rank<=? THEN environment ELSE '' END AS environment
                        FROM selected ORDER BY id
                        """)) {
                    String status = filters.status() == null ? null : filters.status().name();
                    String priority = filters.priority() == null ? null : filters.priority().name();
                    query.setString(1, status); query.setString(2, status);
                    query.setString(3, priority); query.setString(4, priority);
                    query.setObject(5, filters.assigneeId()); query.setObject(6, filters.assigneeId());
                    query.setInt(7, Math.addExact(maximumMembers, 1));
                    for (int index = 8; index <= 12; index++) query.setInt(index, maximumContextReports);
                    try (var row = query.executeQuery()) {
                        while (row.next()) {
                            UUID id = row.getObject("id", UUID.class);
                            reports.add(new ReportInput(id, row.getLong("version"), Status.fromStorage(row.getString("status")),
                                    Priority.valueOf(row.getString("priority")), row.getObject("assignee_id", UUID.class),
                                    row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                                    row.getString("title"), row.getString("description"), row.getString("reproduction_steps"),
                                    row.getString("expected_behavior"), row.getString("actual_behavior"), row.getString("environment"),
                                    row.getLong("context_rank") <= maximumContextReports ? updates(connection, id) : List.of()));
                        }
                    }
                }
                connection.commit();
                // Full identity/version/assignment membership is compared by the service's vector digest.
                return new Snapshot(capturedAt, 0, reports);
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                if (failure instanceof SummaryFailure safe) throw safe;
                throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
            }
        } catch (SQLException failure) { throw new SummaryFailure(FailureCode.AI_UNAVAILABLE); }
    }

    private static List<UpdateInput> updates(Connection connection, UUID report) throws SQLException {
        List<UpdateInput> result = new ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT created_at,text FROM (
                    SELECT id,created_at,text FROM report_history WHERE report_id=?
                    UNION ALL SELECT id,created_at,text FROM report_comments WHERE report_id=?
                ) updates ORDER BY created_at DESC,id DESC LIMIT 3
                """)) {
            query.setObject(1, report); query.setObject(2, report);
            try (var row = query.executeQuery()) {
                while (row.next()) result.add(new UpdateInput(row.getTimestamp("created_at").toInstant(), row.getString("text")));
            }
        }
        return List.copyOf(result);
    }
}
