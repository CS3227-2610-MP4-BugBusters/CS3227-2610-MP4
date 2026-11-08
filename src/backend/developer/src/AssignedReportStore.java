package mp4.developer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads the authenticated Developer's current assigned reports. */
public final class AssignedReportStore {
    public record Report(UUID id, String title, String status) {}
    public record Page(List<Report> items, int page, int pageSize, long totalItems) {}

    private static final int DEFAULT_PAGE_SIZE = 20;
    private final DataSource database;

    public AssignedReportStore(DataSource database) {
        this.database = database;
    }

    public Page firstPage(UUID developerId) throws SQLException {
        return searchPage(developerId, null, null, 1, DEFAULT_PAGE_SIZE);
    }

    public Page searchPage(UUID developerId, String search) throws SQLException {
        return searchPage(developerId, search, null, 1, DEFAULT_PAGE_SIZE);
    }

    public Page searchPage(UUID developerId, String search, String status) throws SQLException {
        return searchPage(developerId, search, status, 1, DEFAULT_PAGE_SIZE);
    }

    public Page searchPage(UUID developerId, String search, String status, int page, int pageSize)
            throws SQLException {
        long offset = ((long) page - 1) * pageSize;
        long pageEnd = offset + pageSize;
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                var items = new ArrayList<Report>();
                long total = 0;
                if (search == null && status == null) {
                    try (var count = connection.prepareStatement(
                            "SELECT count(*) FROM reports WHERE assignee_id = ?")) {
                        count.setObject(1, developerId);
                        try (var row = count.executeQuery()) {
                            row.next();
                            total = row.getLong(1);
                        }
                    }
                    try (var query = connection.prepareStatement(
                            "SELECT id, title, status FROM reports WHERE assignee_id = ? ORDER BY id LIMIT ? OFFSET ?")) {
                        query.setObject(1, developerId);
                        query.setInt(2, pageSize);
                        query.setLong(3, offset);
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) items.add(readReport(rows));
                        }
                    }
                } else {
                    try (var query = connection.prepareStatement(
                            "SELECT id, title, status FROM reports WHERE assignee_id = ? ORDER BY id",
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                        query.setObject(1, developerId);
                        query.setFetchSize(100);
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) {
                                var report = readReport(rows);
                                boolean matchesSearch = search == null
                                        || containsIgnoreCase(report.title(), search)
                                        || report.id().toString().equalsIgnoreCase(search);
                                boolean matchesStatus = status == null || status.equals(report.status());
                                if (matchesSearch && matchesStatus) {
                                    long matchIndex = total;
                                    total++;
                                    if (matchIndex >= offset && matchIndex < pageEnd) items.add(report);
                                }
                            }
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

    private static Report readReport(ResultSet rows) throws SQLException {
        return new Report(rows.getObject("id", UUID.class), rows.getString("title"),
                rows.getString("status"));
    }

    private static boolean containsIgnoreCase(String text, String search) {
        for (int start = 0; start < text.length();) {
            int textOffset = start;
            int searchOffset = 0;
            while (textOffset < text.length() && searchOffset < search.length()) {
                int textCodePoint = text.codePointAt(textOffset);
                int searchCodePoint = search.codePointAt(searchOffset);
                if (!sameIgnoringCase(textCodePoint, searchCodePoint)) break;
                textOffset += Character.charCount(textCodePoint);
                searchOffset += Character.charCount(searchCodePoint);
            }
            if (searchOffset == search.length()) return true;
            start += Character.charCount(text.codePointAt(start));
        }
        return search.isEmpty();
    }

    private static boolean sameIgnoringCase(int first, int second) {
        if (first == second) return true;
        int upperFirst = Character.toUpperCase(first);
        int upperSecond = Character.toUpperCase(second);
        return upperFirst == upperSecond
                || Character.toLowerCase(upperFirst) == Character.toLowerCase(upperSecond);
    }
}
