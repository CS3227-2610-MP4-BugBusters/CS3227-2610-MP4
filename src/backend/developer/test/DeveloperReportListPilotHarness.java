package mp4.developer;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/** Test-only session controls for the unapproved DR-01 route pilot. */
public final class DeveloperReportListPilotHarness {
    private record Session(UUID id, String role, boolean active) {}

    public static void main(String[] args) throws Exception {
        boolean fixtureMode = args.length > 0 && args[0].equals("fixture");
        String jdbcUrl = System.getenv("MP4_TEST_JDBC_URL");
        if (fixtureMode && (jdbcUrl == null || jdbcUrl.isBlank())) {
            throw new IllegalArgumentException("MP4_TEST_JDBC_URL is required in fixture mode");
        }
        var sessions = Map.of(
                "d1-token", new Session(UUID.fromString("00000000-0000-0000-0000-000000000401"), "DEVELOPER", true),
                "d2-token", new Session(UUID.fromString("00000000-0000-0000-0000-000000000402"), "DEVELOPER", true),
                "reporter-token", new Session(UUID.fromString("00000000-0000-0000-0000-000000000403"), "REPORTER", true),
                "inactive-token", new Session(UUID.fromString("00000000-0000-0000-0000-000000000404"), "DEVELOPER", false));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                Session session = auth != null && auth.startsWith("Bearer ")
                        ? sessions.get(auth.substring("Bearer ".length())) : null;
                if (session == null) {
                    exchange.sendResponseHeaders(401, -1);
                } else if (exchange.getRequestURI().getPath().equals("/__test/session")) {
                    byte[] body = ("{\"id\":\"" + session.id() + "\",\"role\":\"" + session.role()
                            + "\",\"active\":" + session.active() + "}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else if (exchange.getRequestURI().getPath().equals("/api/developer/reports")) {
                    if (!fixtureMode) {
                        // Baseline mode preserves the actual missing-route result.
                        exchange.sendResponseHeaders(404, -1);
                    } else if (!session.role().equals("DEVELOPER") || !session.active()) {
                        exchange.sendResponseHeaders(403, -1);
                    } else {
                        sendAssignedReports(exchange, jdbcUrl, session.id());
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            }
        });
        server.start();
        System.out.println("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void sendAssignedReports(com.sun.net.httpserver.HttpExchange exchange,
            String jdbcUrl, UUID developerId) throws java.io.IOException {
        String password = System.getenv("MP4_TEST_DB_PASSWORD");
        String sql = "SELECT id, title, status FROM dr01_pilot_reports WHERE assignee_id = ? ORDER BY id";
        try (var connection = password == null
                        ? DriverManager.getConnection(jdbcUrl)
                        : DriverManager.getConnection(jdbcUrl, System.getenv("MP4_TEST_DB_USER"), password);
                var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, developerId);
            int total;
            try (var count = connection.prepareStatement(
                    "SELECT count(*) FROM dr01_pilot_reports WHERE assignee_id = ?")) {
                count.setObject(1, developerId);
                try (var result = count.executeQuery()) { result.next(); total = result.getInt(1); }
            }
            try (var rows = statement.executeQuery()) {
                StringBuilder json = new StringBuilder("{\"items\":[");
                boolean first = true;
                while (rows.next()) {
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"id\":\"").append(rows.getObject("id"))
                            .append("\",\"title\":\"").append(escape(rows.getString("title")))
                            .append("\",\"status\":\"").append(escape(rows.getString("status")))
                            .append("\"}");
                }
                json.append("],\"page\":1,\"pageSize\":20,\"totalItems\":").append(total).append('}');
                byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        } catch (SQLException e) {
            byte[] body = "{\"error\":\"test fixture database unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
