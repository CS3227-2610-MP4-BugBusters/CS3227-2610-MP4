package mp4.developer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads the authenticated Developer's current assigned reports. */
public final class AssignedReportStore {
    public record Report(UUID id, String title, String status) {}
    public record Page(List<Report> items, int page, int pageSize, long totalItems) {}

    private static final int PAGE_SIZE = 20;
    private final DataSource database;

    public AssignedReportStore(DataSource database) {
        this.database = database;
    }

    public Page firstPage(UUID developerId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                long total;
                try (var count = connection.prepareStatement(
                        "SELECT count(*) FROM reports WHERE assignee_id = ?")) {
                    count.setObject(1, developerId);
                    try (var row = count.executeQuery()) {
                        row.next();
                        total = row.getLong(1);
                    }
                }

                var items = new ArrayList<Report>();
                try (var query = connection.prepareStatement(
                        "SELECT id, title, status FROM reports WHERE assignee_id = ? ORDER BY id LIMIT ?")) {
                    query.setObject(1, developerId);
                    query.setInt(2, PAGE_SIZE);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            items.add(new Report(rows.getObject("id", UUID.class),
                                    rows.getString("title"), rows.getString("status")));
                        }
                    }
                }
                connection.commit();
                return new Page(List.copyOf(items), 1, PAGE_SIZE, total);
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
}
