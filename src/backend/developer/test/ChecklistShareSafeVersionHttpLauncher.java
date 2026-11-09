package mp4.developer;

import org.postgresql.ds.PGSimpleDataSource;

/** Test-only host exposing only Share for the safe-version regression. */
public final class ChecklistShareSafeVersionHttpLauncher {
    public static void main(String[] args) throws Exception {
        var database = new PGSimpleDataSource();
        database.setURL(System.getenv("MP4_TEST_JDBC_URL"));
        String password = System.getenv("MP4_TEST_DB_PASSWORD");
        if (password != null) database.setPassword(password);
        var handler = new ChecklistShareHttpHandler(new JdbcChecklistShareService(database));
        ChecklistShareHttpHarness.start(handler::handle);
    }
}
