package mp4.maintainer.storage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Registers the canonical report and checklist migrations under one shared history. */
public final class SharedSchemaBootstrap {
    private static final String HISTORY = "mp4_shared_schema_history";
    private SharedSchemaBootstrap() {}

    public static void initialize(DataSource dataSource) throws SQLException {
        boolean historyExists;
        boolean hasTables;
        int manualVersion;
        try (Connection c = dataSource.getConnection()) {
            historyExists = tableExists(c, HISTORY);
            hasTables = hasTables(c);
            manualVersion = historyExists ? 0 : inspectManualSchema(c);
        }
        Flyway flyway = Flyway.configure().dataSource(dataSource).defaultSchema("public")
                .locations("classpath:db/shared").table(HISTORY)
                .baselineVersion(Integer.toString(manualVersion)).load();
        if (!historyExists && hasTables) flyway.baseline();
        flyway.migrate();
    }

    /** Returns the highest canonical legacy version after validating every present shared table. */
    private static int inspectManualSchema(Connection c) throws SQLException {
        boolean reports = tableExists(c, "reports");
        boolean checklists = tableExists(c, "investigation_checklists");
        boolean proposals = tableExists(c, "resolution_proposals");
        if (!reports && !checklists && !proposals) return 0;
        if (!reports) throw new IllegalStateException("incompatible partial shared schema");

        requireColumns(c, "reports", Map.ofEntries(
                Map.entry("id", "uuid!"), Map.entry("title", "text!"), Map.entry("description", "text!"),
                Map.entry("reporter_id", "uuid!"), Map.entry("assignee_id", "uuid"), Map.entry("status", "text!"),
                Map.entry("version", "int8!"), Map.entry("current_proposal_id", "uuid")));
        requireDefault(c, "reports", "status", "'SUBMITTED'::text");
        requireDefault(c, "reports", "version", "0");
        requirePrimaryKey(c, "reports", "id");
        requireCheck(c, "reports", "status", "SUBMITTED", "ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED", "CLOSED");
        requireCheck(c, "reports", "version", "version >= 0");
        if (checklists) {
            requireColumns(c, "investigation_checklists", Map.of(
                    "report_id", "uuid!", "draft_steps", "_text!", "draft_revision", "int8!", "shared_steps", "_text!"));
            requireDefault(c, "investigation_checklists", "draft_steps", "'{}'::text[]");
            requireDefault(c, "investigation_checklists", "draft_revision", "0");
            requireDefault(c, "investigation_checklists", "shared_steps", "'{}'::text[]");
            requirePrimaryKey(c, "investigation_checklists", "report_id");
            requireForeignKey(c, "investigation_checklists", "report_id", "reports", "id");
            requireCheck(c, "investigation_checklists", "draft_revision", "draft_revision >= 0");
        }
        if (proposals) {
            requireColumns(c, "resolution_proposals", Map.of(
                    "id", "uuid!", "report_id", "uuid!", "status", "text!", "version", "int8!", "contents", "text!"));
            requirePrimaryKey(c, "resolution_proposals", "id");
            requireForeignKey(c, "resolution_proposals", "report_id", "reports", "id");
        }
        return checklists ? 2 : 1;
    }

    private static void requireDefault(Connection c, String table, String column, String expected) throws SQLException {
        String actual = null;
        try (var statement = c.prepareStatement(
                "SELECT column_default FROM information_schema.columns " +
                        "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) actual = rows.getString("column_default");
            }
        }
        if (!expected.equals(actual))
            throw new IllegalStateException("incompatible shared schema default: " + table + "." + column);
    }

    private static void requireColumns(Connection c, String table, Map<String, String> expected) throws SQLException {
        Map<String, String> actual = new HashMap<>();
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet columns = meta.getColumns(null, "public", table, null)) {
            while (columns.next()) actual.put(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                    columns.getString("TYPE_NAME").toLowerCase(Locale.ROOT) + (columns.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls ? "!" : ""));
        }
        for (var entry : expected.entrySet()) {
            if (!entry.getValue().equals(actual.get(entry.getKey())))
                throw new IllegalStateException("incompatible shared schema: " + table + "." + entry.getKey());
        }
    }

    private static void requirePrimaryKey(Connection c, String table, String column) throws SQLException {
        try (ResultSet keys = c.getMetaData().getPrimaryKeys(null, "public", table)) {
            if (!keys.next() || !column.equalsIgnoreCase(keys.getString("COLUMN_NAME")) || keys.next())
                throw new IllegalStateException("incompatible shared schema primary key: " + table);
        }
    }

    private static void requireCheck(Connection c, String table, String column, String... values) throws SQLException {
        String sql = "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'public." + table + "'::regclass AND contype='c'";
        try (var statement = c.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            String defs = "";
            while (rows.next()) defs += " " + rows.getString(1).toLowerCase(Locale.ROOT).replace("::text", "");
            if (defs.isBlank() || !defs.contains(column.toLowerCase(Locale.ROOT)))
                throw new IllegalStateException("incompatible shared schema check constraint: " + table + "." + column);
            for (String value : values) if (!defs.contains(value.toLowerCase(Locale.ROOT)))
                throw new IllegalStateException("incompatible shared schema check constraint: " + table + "." + column);
        }
    }

    private static void requireForeignKey(Connection c, String table, String column, String target, String targetColumn)
            throws SQLException {
        try (ResultSet keys = c.getMetaData().getImportedKeys(null, "public", table)) {
            while (keys.next()) if (column.equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
                    && target.equalsIgnoreCase(keys.getString("PKTABLE_NAME"))
                    && targetColumn.equalsIgnoreCase(keys.getString("PKCOLUMN_NAME"))) return;
        }
        throw new IllegalStateException("incompatible shared schema foreign key: " + table + "." + column);
    }

    private static boolean tableExists(Connection c, String name) throws SQLException {
        try (ResultSet rows = c.getMetaData().getTables(null, "public", name, new String[]{"TABLE"})) { return rows.next(); }
    }

    private static boolean hasTables(Connection c) throws SQLException {
        try (ResultSet rows = c.getMetaData().getTables(null, "public", "%", new String[]{"TABLE"})) { return rows.next(); }
    }
}
