package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Verifies fresh and Reporter V4 databases advance through shared migrations safely. */
class SharedMigrationVersionTest {
    private record Database(String url, String user, String password, String name, String adminUrl)
            implements AutoCloseable {
        static Database create() throws Exception {
            var config = ProvisioningDatabase.Config.fromEnvironment(Map.of(
                    "MP4_AUTH_JDBC_URL", required("MP4_AUTH_TEST_JDBC_URL"),
                    "MP4_AUTH_DB_USER", required("MP4_AUTH_TEST_DB_USER"),
                    "MP4_AUTH_DB_PASSWORD", required("MP4_AUTH_TEST_DB_PASSWORD")));
            URI source = new URI(config.url().substring("jdbc:".length()));
            String name = "mp4_shared_migration_" + UUID.randomUUID().toString().replace("-", "");
            String authority = source.getRawAuthority();
            String adminUrl = "jdbc:postgresql://" + authority + "/postgres";
            String url = "jdbc:postgresql://" + authority + "/" + name;
            try (Connection admin = DriverManager.getConnection(adminUrl, config.user(), config.password());
                 Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + name);
            }
            return new Database(url, config.user(), config.password(), name, adminUrl);
        }

        Flyway flyway() {
            return flyway(null);
        }

        Flyway flyway(String target) {
            var configuration = Flyway.configure().dataSource(url, user, password).defaultSchema("public")
                    .locations("classpath:db/shared").table("mp4_shared_schema_history");
            if (target != null) configuration.target(target);
            return configuration.load();
        }

        Connection connect() throws Exception { return DriverManager.getConnection(url, user, password); }

        @Override public void close() throws Exception {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            }
        }
    }

    @Test void freshDatabaseAppliesUniqueSharedMigrationsThroughNotifications() throws Exception {
        try (Database db = Database.create()) {
            var flyway = db.flyway();
            db.flyway().migrate();
            assertEquals("6", flyway.info().current().getVersion().getVersion());
            try (Connection c = db.connect(); Statement s = c.createStatement()) {
                assertTrue(tableExists(c, "reports"));
                assertTrue(tableExists(c, "developer_private_notes"));
                assertTrue(tableExists(c, "developer_notifications"));
                s.execute("INSERT INTO reports(id,title,description,reporter_id) VALUES ('" + UUID.randomUUID()
                        + "','t','d','" + UUID.randomUUID() + "')");
            }
        }
    }

    @Test void ReporterV4UpgradePreservesExistingReportAndAddsDeveloperStorage() throws Exception {
        try (Database db = Database.create()) {
            var flyway = db.flyway("4");
            flyway.migrate();
            UUID reportId = UUID.randomUUID();
            UUID reporterId = UUID.randomUUID();
            try (Connection c = db.connect(); var insert = c.prepareStatement(
                    "INSERT INTO reports(id,title,description,reporter_id) VALUES (?,?,?,?)")) {
                insert.setObject(1, reportId);
                insert.setString(2, "Reporter V4 title");
                insert.setString(3, "preserve this report");
                insert.setObject(4, reporterId);
                insert.executeUpdate();
            }

            db.flyway().migrate();

            try (Connection c = db.connect(); var query = c.prepareStatement(
                    "SELECT title,description,reporter_id,created_at,updated_at FROM reports WHERE id=?")) {
                query.setObject(1, reportId);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("Reporter V4 title", row.getString("title"));
                    assertEquals("preserve this report", row.getString("description"));
                    assertEquals(reporterId, row.getObject("reporter_id", UUID.class));
                    assertNotNull(row.getTimestamp("created_at"));
                    assertNotNull(row.getTimestamp("updated_at"));
                    assertFalse(row.next());
                }
                assertTrue(tableExists(c, "developer_private_notes"));
                assertTrue(tableExists(c, "developer_notifications"));
            }
            assertEquals("6", flyway.info().current().getVersion().getVersion());
        }
    }

    @Test void sharedMigrationVersionsAreUniqueAndPreserveReporterV4AndDeveloperV5V6() {
        var config = ProvisioningDatabase.Config.fromEnvironment(Map.of(
                "MP4_AUTH_JDBC_URL", System.getenv("MP4_AUTH_TEST_JDBC_URL"),
                "MP4_AUTH_DB_USER", System.getenv("MP4_AUTH_TEST_DB_USER"),
                "MP4_AUTH_DB_PASSWORD", System.getenv("MP4_AUTH_TEST_DB_PASSWORD")));
        var flyway = Flyway.configure()
                .dataSource(config.url(), config.user(), config.password())
                .locations("classpath:db/shared")
                .load();
        var migrations = java.util.Arrays.asList(flyway.info().all());
        var duplicates = migrations.stream().collect(java.util.stream.Collectors.groupingBy(
                migration -> migration.getVersion().getVersion(), java.util.stream.Collectors.counting()));
        assertTrue(duplicates.values().stream().allMatch(count -> count == 1),
                () -> "shared Flyway versions must be unique: " + duplicates);
        assertTrue(migrations.stream().anyMatch(m -> m.getVersion().getVersion().equals("4")
                && m.getScript().contains("reporter_report_timestamps")), "Reporter timestamps retain shared V4");
        assertTrue(migrations.stream().anyMatch(m -> m.getVersion().getVersion().equals("5")
                && m.getScript().contains("developer_private_notes")), "Developer private notes use shared V5");
        assertTrue(migrations.stream().anyMatch(m -> m.getVersion().getVersion().equals("6")
                && m.getScript().toLowerCase().contains("notification")), "Developer notifications use shared V6");
    }

    private static boolean tableExists(Connection c, String table) throws Exception {
        try (var query = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            query.setString(1, "public." + table);
            try (var result = query.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " is required; use the disposable runner");
        assertFalse(value.isBlank(), name + " must not be blank");
        return value;
    }
}
