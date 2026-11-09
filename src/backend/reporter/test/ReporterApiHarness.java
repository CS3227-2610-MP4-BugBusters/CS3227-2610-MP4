package mp4.reporter;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/** Test-only loopback HTTP harness with a synthetic session. Never parses request bodies. */
public final class ReporterApiHarness {
    private record Actor(UUID id, String role, boolean active) {}

    public static void main(String[] args) throws Exception {
        Map<String, Actor> actors = new HashMap<>();
        String spec = System.getenv("MP4_TEST_ACTORS");
        if (spec != null) {
            for (String entry : spec.split(";")) {
                if (entry.isBlank()) continue;
                String[] kv = entry.split("=", 2);
                String[] parts = kv[1].split(":");
                actors.put(kv[0], new Actor(UUID.fromString(parts[0]), parts[1], Boolean.parseBoolean(parts[2])));
            }
        }
        var ds = new PGSimpleDataSource();
        ds.setURL(System.getenv("MP4_TEST_JDBC_URL"));
        Object handler = null;
        Method handle = null;
        try {
            Class<?> type = Class.forName("mp4.reporter.ReporterReportsHttpHandler");
            handler = type.getConstructor(javax.sql.DataSource.class).newInstance(ds);
            handle = type.getMethod("handle", com.sun.net.httpserver.HttpExchange.class, UUID.class, String.class,
                    boolean.class);
        } catch (ClassNotFoundException missing) {
            handler = null;
        }
        final Object h = handler;
        final Method m = handle;
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            try {
                if (h == null) {
                    byte[] out = "{\"code\":\"NOT_IMPLEMENTED\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(501, out.length);
                    exchange.getResponseBody().write(out);
                    return;
                }
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                Actor actor = null;
                if (auth != null && auth.startsWith("Bearer ")) actor = actors.get(auth.substring(7));
                m.invoke(h, exchange, actor == null ? null : actor.id(), actor == null ? "REPORTER" : actor.role(),
                        actor != null && actor.active());
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        System.out.println("http://127.0.0.1:" + server.getAddress().getPort());
        System.out.flush();
    }
}
