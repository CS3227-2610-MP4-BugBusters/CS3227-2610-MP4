package mp4.developer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import mp4.developer.ChecklistDraftStore.Actor;
import mp4.developer.ChecklistDraftStore.Role;

/** Loopback host for testing the Save HTTP handler before shared authentication lands. */
public final class ChecklistSaveHttpHarness {
    @FunctionalInterface
    interface AuthenticatedHandler {
        void handle(HttpExchange exchange, Actor actor) throws IOException;
    }

    public static void main(String[] args) throws IOException {
        start(null);
    }

    static HttpServer start(AuthenticatedHandler application) throws IOException {
        var actor = new Actor(UUID.fromString(requiredEnv("MP4_TEST_DEVELOPER_ID")), Role.DEVELOPER, true);
        var session = List.of("Bearer " + requiredEnv("MP4_TEST_SESSION_TOKEN"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!session.equals(exchange.getRequestHeaders().get("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                } else if (exchange.getRequestMethod().equals("GET")
                        && exchange.getRequestURI().getPath().equals("/__test/session")) {
                    sendSession(exchange, actor);
                } else if (application != null) {
                    application.handle(exchange, actor);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            }
        });
        server.start();
        System.out.println("http://127.0.0.1:" + server.getAddress().getPort());
        return server;
    }

    private static void sendSession(HttpExchange exchange, Actor actor) throws IOException {
        byte[] body = """
                {"id":"%s","role":"%s","active":%s}
                """.formatted(actor.id(), actor.role(), actor.active()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + name);
        }
        return value;
    }
}
