package mp4.developer;

import org.postgresql.ds.PGSimpleDataSource;

/** Test-only launcher mounting Save on the synthetic-session HTTP harness. */
public final class ChecklistSaveHttpLauncher {
    public static void main(String[] args) throws Exception {
        var database = new PGSimpleDataSource();
        database.setURL(requiredEnv("MP4_TEST_JDBC_URL"));
        String password = System.getenv("MP4_TEST_DB_PASSWORD");
        if (password != null) {
            database.setPassword(password);
        }
        var store = new ChecklistDraftStore(database);
        var handler = new ChecklistDraftHttpHandler(store);
        ChecklistSaveHttpHarness.start(handler::handle);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + name);
        }
        return value;
    }
}
