package mp4.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/** Controls the isolated runner before any authentication behavior tests are added. */
class RunnerSetupTest {
    @Test
    void connectsToDisposableDatabaseAndHasRequiredRuntimeLibraries() throws Exception {
        String url = required("MP4_AUTH_TEST_JDBC_URL");
        String user = required("MP4_AUTH_TEST_DB_USER");
        String password = required("MP4_AUTH_TEST_DB_PASSWORD");
        assertNotNull(PasswordEncoder.class);
        assertNotNull(JdbcIndexedSessionRepository.class);
        assertNotNull(org.flywaydb.core.Flyway.class);
        assertNotNull(org.apache.catalina.startup.Tomcat.class);
        org.flywaydb.core.Flyway.configure().dataSource(url, user, password)
                .defaultSchema("public").locations("classpath:db/shared")
                .table("mp4_shared_schema_history").load().migrate();
        try (var connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            assertTrue(tableExists(connection, "reports"), "shared reports migration was not applied");
            assertTrue(tableExists(connection, "investigation_checklists"), "developer checklist migration was not applied");
        }
    }

    private static boolean tableExists(java.sql.Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, "public." + table);
            try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " is required; use the disposable runner");
        assertFalse(value.isBlank(), name + " must not be blank");
        return value;
    }
}
