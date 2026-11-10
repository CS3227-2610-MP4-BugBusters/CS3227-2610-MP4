package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mp4.platform.AuthApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Real PostgreSQL startup coverage for additive Maintainer shared storage. */
class SharedSchemaStartupIntegrationTest {
    private static final Set<String> REPORT_COLUMNS = Set.of(
            "id", "title", "description", "reporter_id", "assignee_id", "status", "version",
            "current_proposal_id", "priority", "created_at", "updated_at", "reproduction_steps",
            "expected_behavior", "actual_behavior", "environment");
    private record LegacySnapshot(Instant createdAt, Instant updatedAt) {}

    private record Database(ProvisioningDatabase.Config config, String name, String adminUrl) implements AutoCloseable {
        static Database create() throws Exception {
            ProvisioningDatabase.Config configured = ProvisioningDatabase.Config.fromEnvironment(Map.of(
                    "MP4_AUTH_JDBC_URL", required("MP4_AUTH_TEST_JDBC_URL"),
                    "MP4_AUTH_DB_USER", required("MP4_AUTH_TEST_DB_USER"),
                    "MP4_AUTH_DB_PASSWORD", required("MP4_AUTH_TEST_DB_PASSWORD")));
            URI source = new URI(configured.url().substring("jdbc:".length()));
            String name = "mp4_shared_startup_" + UUID.randomUUID().toString().replace("-", "");
            String adminUrl = "jdbc:postgresql://" + source.getRawAuthority() + "/postgres";
            String isolatedUrl = "jdbc:postgresql://" + source.getRawAuthority() + "/" + name;
            try (Connection admin = DriverManager.getConnection(adminUrl, configured.user(), configured.password());
                 Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + name);
            }
            return new Database(new ProvisioningDatabase.Config(isolatedUrl, configured.user(), configured.password()), name, adminUrl);
        }

        void initializeAuthOnly() { ProvisioningDatabase.initialize(config); }
        Connection connect() throws SQLException { return DriverManager.getConnection(config.url(), config.user(), config.password()); }
        public void close() throws Exception {
            try (Connection admin = DriverManager.getConnection(adminUrl, config.user(), config.password());
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            }
        }
    }

    private static final class Server implements AutoCloseable {
        final ConfigurableApplicationContext context;
        Server(Database db) {
            context = new SpringApplicationBuilder(AuthApplication.class)
                    .properties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                            "spring.main.banner-mode", "off", "logging.level.root", "ERROR",
                            "mp4.auth.local", "true", "mp4.auth.jdbc-url", db.config.url(),
                            "mp4.auth.db-user", db.config.user(), "mp4.auth.db-password", db.config.password(),
                            "mp4.auth.rate-limit-secret", UUID.randomUUID().toString() + UUID.randomUUID()))
                    .run();
        }
        public void close() { context.close(); }
    }

    @Test void freshAuthDatabaseStartsWithSharedReportsAndDeveloperStorage() throws Exception {
        try (Database db = Database.create()) {
            db.initializeAuthOnly();
            try (Server ignored = new Server(db); Connection c = db.connect()) {
                assertTrue(tableExists(c, "reports"), "startup must create the shared reports table");
                assertTrue(tableExists(c, "investigation_checklists"), "startup must create shared checklists");
                assertTrue(tableExists(c, "resolution_proposals"), "startup must create the canonical proposal store");
                assertTrue(columns(c, "reports").containsAll(REPORT_COLUMNS), "reports must retain its canonical fields and expose Maintainer fields");
                assertEquals(Set.of("report_id", "draft_steps", "draft_revision", "shared_steps"),
                        columns(c, "investigation_checklists"), "checklist storage must retain the Developer schema");
                assertEquals(Set.of("id", "report_id", "status", "version", "contents"),
                        columns(c, "resolution_proposals"), "proposal core must retain its five-column contract");
            }
        }
    }

    @Test void startupAndRestartPreserveKnownLegacyRowsAndAddDefaults() throws Exception {
        try (Database db = Database.create()) {
            db.initializeAuthOnly();
            UUID reportId = UUID.randomUUID();
            UUID reporterId = UUID.randomUUID(), assigneeId = UUID.randomUUID(), proposalId = UUID.randomUUID();
            try (Connection c = db.connect(); Statement s = c.createStatement()) {
                // This is the canonical eight-column reports definition from shared migration 001.
                s.execute("CREATE TABLE reports (id uuid PRIMARY KEY, title text NOT NULL, description text NOT NULL, " +
                        "reporter_id uuid NOT NULL, assignee_id uuid, status text NOT NULL DEFAULT 'SUBMITTED' " +
                        "CHECK (status IN ('SUBMITTED','ASSIGNED','IN_PROGRESS','RESOLUTION_PROPOSED','CLOSED')), " +
                        "version bigint NOT NULL DEFAULT 0 CHECK (version >= 0), current_proposal_id uuid)");
                s.execute("CREATE TABLE investigation_checklists (report_id uuid PRIMARY KEY REFERENCES reports(id), " +
                        "draft_steps text[] NOT NULL DEFAULT '{}', draft_revision bigint NOT NULL DEFAULT 0 CHECK (draft_revision >= 0), " +
                        "shared_steps text[] NOT NULL DEFAULT '{}')");
                s.execute("CREATE TABLE resolution_proposals (id uuid PRIMARY KEY, report_id uuid NOT NULL REFERENCES reports(id), " +
                        "status text NOT NULL, version bigint NOT NULL, contents text NOT NULL)");
            }
            try (Connection c = db.connect(); var insert = c.prepareStatement(
                    "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version,current_proposal_id) " +
                            "VALUES(?,?,?,?,?,'ASSIGNED',7,?)")) {
                insert.setObject(1, reportId); insert.setString(2, "Legacy title"); insert.setString(3, "Legacy description");
                insert.setObject(4, reporterId); insert.setObject(5, assigneeId); insert.setObject(6, proposalId);
                insert.executeUpdate();
            }
            try (Connection c = db.connect(); var insert = c.prepareStatement(
                    "INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?,?,?)")) {
                insert.setObject(1, reportId); insert.setArray(2, c.createArrayOf("text", new String[]{"private draft"}));
                insert.setLong(3, 11); insert.setArray(4, c.createArrayOf("text", new String[]{"published step"})); insert.executeUpdate();
            }

            Instant startupStarted = Instant.now();
            LegacySnapshot first;
            try (Server ignored = new Server(db)) { first = assertLegacyRowAndDefaults(db, reportId, reporterId, assigneeId, proposalId, startupStarted); }
            try (Server ignored = new Server(db)) {
                LegacySnapshot restarted = assertLegacyRowAndDefaults(db, reportId, reporterId, assigneeId, proposalId, startupStarted);
                assertEquals(first, restarted, "restart must not rewrite the legacy migration timestamps");
            }
        }
    }

    @Test void startupRejectsAnIncompatiblePartialReportsTable() throws Exception {
        try (Database db = Database.create()) {
            db.initializeAuthOnly();
            try (Connection c = db.connect(); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE reports (id uuid PRIMARY KEY)");
            }
            assertThrows(RuntimeException.class, () -> { try (Server ignored = new Server(db)) { /* close any unexpected successful startup */ } },
                    "startup must reject a partial manual reports schema instead of silently baselining it");
        }
    }

    @Test void startupRejectsManualSharedTablesMissingCanonicalWriteDefaultsWithoutMutation() throws Exception {
        UUID reportId = UUID.randomUUID();
        for (String missingDefault : List.of("investigation_checklists.shared_steps", "reports.status", "reports.version",
                "investigation_checklists.draft_steps", "investigation_checklists.draft_revision")) {
            try (Database db = Database.create()) {
                db.initializeAuthOnly();
                try (Connection c = db.connect(); Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE reports (id uuid PRIMARY KEY, title text NOT NULL, description text NOT NULL, " +
                            "reporter_id uuid NOT NULL, assignee_id uuid, status text NOT NULL DEFAULT 'SUBMITTED' " +
                            "CHECK (status IN ('SUBMITTED','ASSIGNED','IN_PROGRESS','RESOLUTION_PROPOSED','CLOSED')), " +
                            "version bigint NOT NULL DEFAULT 0 CHECK (version >= 0), current_proposal_id uuid)");
                    s.execute("CREATE TABLE investigation_checklists (report_id uuid PRIMARY KEY REFERENCES reports(id), " +
                            "draft_steps text[] NOT NULL DEFAULT '{}', draft_revision bigint NOT NULL DEFAULT 0 CHECK (draft_revision >= 0), " +
                            "shared_steps text[] NOT NULL DEFAULT '{}')");
                    String[] target = missingDefault.split("\\.");
                    s.execute("ALTER TABLE " + target[0] + " ALTER COLUMN " + target[1] + " DROP DEFAULT");
                    s.execute("INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version) " +
                            "VALUES ('" + reportId + "','legacy title','legacy description','" + UUID.randomUUID() + "','" +
                            UUID.randomUUID() + "','ASSIGNED',7)");
                    s.execute("INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) " +
                            "VALUES ('" + reportId + "',ARRAY['private draft'],11,ARRAY['published step'])");
                }

                assertThrows(RuntimeException.class, () -> { try (Server ignored = new Server(db)) { /* close unexpected success */ } },
                        "startup must reject a manual shared schema missing " + missingDefault + " default");

                try (Connection c = db.connect(); Statement s = c.createStatement()) {
                    assertFalse(tableExists(c, "mp4_shared_schema_history"),
                            "rejected manual schema must not receive a history baseline");
                    assertFalse(columns(c, "reports").contains("priority"),
                            "rejected manual schema must not receive partial V3 columns");
                    assertFalse(tableExists(c, "resolution_proposals"),
                            "rejected manual schema must not receive partial V3 tables");
                    try (ResultSet row = s.executeQuery("SELECT title,status,version FROM reports WHERE id='" + reportId + "'")) {
                        assertTrue(row.next());
                        assertEquals("legacy title", row.getString("title"));
                        assertEquals("ASSIGNED", row.getString("status"));
                        assertEquals(7, row.getLong("version"));
                        assertFalse(row.next());
                    }
                    try (ResultSet row = s.executeQuery("SELECT draft_steps,draft_revision,shared_steps FROM investigation_checklists WHERE report_id='" + reportId + "'")) {
                        assertTrue(row.next());
                        assertArrayEquals(new String[]{"private draft"}, (String[]) row.getArray("draft_steps").getArray());
                        assertEquals(11, row.getLong("draft_revision"));
                        assertArrayEquals(new String[]{"published step"}, (String[]) row.getArray("shared_steps").getArray());
                        assertFalse(row.next());
                    }
                }
            }
        }
    }

    private static LegacySnapshot assertLegacyRowAndDefaults(Database db, UUID reportId, UUID reporterId, UUID assigneeId,
                                                               UUID proposalId, Instant migrationStarted) throws Exception {
        LegacySnapshot snapshot;
        try (Connection c = db.connect()) {
            assertTrue(columns(c, "reports").containsAll(REPORT_COLUMNS), "legacy reports must receive the additive schema");
            try (var query = c.prepareStatement("SELECT title,description,reporter_id,assignee_id,status,version,current_proposal_id,priority,created_at,updated_at FROM reports WHERE id=?")) {
                query.setObject(1, reportId);
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next(), "legacy report row must survive startup");
                    assertEquals("Legacy title", row.getString("title"));
                    assertEquals("Legacy description", row.getString("description"));
                    assertEquals(reporterId, row.getObject("reporter_id", UUID.class));
                    assertEquals(assigneeId, row.getObject("assignee_id", UUID.class));
                    assertEquals("ASSIGNED", row.getString("status"));
                    assertEquals(7, row.getLong("version"));
                    assertEquals(proposalId, row.getObject("current_proposal_id", UUID.class));
                    assertEquals("MEDIUM", row.getString("priority"));
                    Instant created = row.getTimestamp("created_at").toInstant();
                    Instant updated = row.getTimestamp("updated_at").toInstant();
                    assertFalse(created.isBefore(migrationStarted), "legacy created_at records migration time");
                    assertFalse(updated.isBefore(migrationStarted), "legacy updated_at records migration time");
                    assertEquals(created, updated);
                    assertFalse(row.next());
                    snapshot = new LegacySnapshot(created, updated);
                }
            }
            try (var query = c.prepareStatement("SELECT draft_steps,draft_revision,shared_steps FROM investigation_checklists WHERE report_id=?")) {
                query.setObject(1, reportId);
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertArrayEquals(new String[]{"private draft"}, (String[]) row.getArray("draft_steps").getArray());
                    assertEquals(11, row.getLong("draft_revision"));
                    assertArrayEquals(new String[]{"published step"}, (String[]) row.getArray("shared_steps").getArray());
                }
            }
        }
        return snapshot;
    }

    private static Set<String> columns(Connection c, String table) throws SQLException {
        try (var query = c.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=?")) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                var result = new java.util.HashSet<String>(); while (rows.next()) result.add(rows.getString(1)); return result;
            }
        }
    }
    private static boolean tableExists(Connection c, String table) throws SQLException { return !columns(c, table).isEmpty(); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("required disposable PostgreSQL configuration is missing");
        return value;
    }
}
