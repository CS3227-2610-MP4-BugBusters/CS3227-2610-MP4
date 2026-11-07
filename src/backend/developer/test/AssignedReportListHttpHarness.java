package mp4.developer;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** Test-only synthetic sessions mounting the assigned-report application handler. */
public final class AssignedReportListHttpHarness {
    private record Session(UUID id, ChecklistDraftStore.Role role, boolean active) {}

    public static void main(String[] args) throws Exception {
        String jdbcUrl = requiredEnv("MP4_TEST_JDBC_URL");
        DataSource database = new DataSource() {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                String password = System.getenv("MP4_TEST_DB_PASSWORD");
                return password == null ? DriverManager.getConnection(jdbcUrl)
                        : DriverManager.getConnection(jdbcUrl, System.getenv("MP4_TEST_DB_USER"), password);
            }
            @Override public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException {
                return DriverManager.getConnection(jdbcUrl, user, password);
            }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { throw new java.sql.SQLException("Not a wrapper"); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
        var sessions = Map.of(
                "d1-token", new Session(id("401"), ChecklistDraftStore.Role.DEVELOPER, true),
                "d2-token", new Session(id("402"), ChecklistDraftStore.Role.DEVELOPER, true),
                "empty-token", new Session(id("405"), ChecklistDraftStore.Role.DEVELOPER, true),
                "reporter-token", new Session(id("403"), ChecklistDraftStore.Role.REPORTER, true),
                "maintainer-token", new Session(id("406"), ChecklistDraftStore.Role.MAINTAINER, true),
                "inactive-token", new Session(id("404"), ChecklistDraftStore.Role.DEVELOPER, false));
        var handler = new AssignedReportListHttpHandler(new AssignedReportStore(database));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                Session session = authorization != null && authorization.startsWith("Bearer ")
                        ? sessions.get(authorization.substring("Bearer ".length())) : null;
                handler.handle(exchange, session == null ? null
                        : new ChecklistDraftStore.Actor(session.id(), session.role(), session.active()));
            }
        });
        server.start();
        System.out.println("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static UUID id(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-000000000" + suffix);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
