package mp4.developer;

import org.postgresql.ds.PGSimpleDataSource;

/** Test-only launcher mounting the private-notes route with a synthetic session actor. */
public final class PrivateNotesHttpLauncher {
    public static void main(String[] args) throws Exception {
        String jdbcUrl = requiredEnv("MP4_TEST_JDBC_URL");
        var database = new PGSimpleDataSource();
        database.setURL(jdbcUrl);
        var handler = new PrivateNotesHttpHandler(new JdbcPrivateNotesService(database));
        ChecklistSaveHttpHarness.start(handler::handle);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
