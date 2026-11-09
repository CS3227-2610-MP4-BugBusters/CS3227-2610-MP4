package mp4.developer;

import org.postgresql.ds.PGSimpleDataSource;

/** Test-only launcher mounting Share on the synthetic-session HTTP harness. */
public final class ChecklistShareHttpLauncher {
    public static void main(String[] args) throws Exception {
        var database = new PGSimpleDataSource();
        database.setURL(requiredEnv("MP4_TEST_JDBC_URL"));
        String password = System.getenv("MP4_TEST_DB_PASSWORD");
        if (password != null) database.setPassword(password);
        var service = new JdbcChecklistShareService(database);
        var handler = new ChecklistShareHttpHandler(service);
        ChecklistShareHttpHarness.start(handler::handle);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
