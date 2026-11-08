package mp4.developer;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Test-only synthetic sessions; deliberately does not mount the reports handler. */
public final class AssignedReportsBaselineHarness {
    private record Actor(UUID id, String role, boolean active) {}

    private static final Map<String, Actor> SESSIONS = Map.of(
            "d1-token", new Actor(UUID.fromString("00000000-0000-0000-0000-000000000401"), "DEVELOPER", true),
            "d2-token", new Actor(UUID.fromString("00000000-0000-0000-0000-000000000402"), "DEVELOPER", true),
            "empty-token", new Actor(UUID.fromString("00000000-0000-0000-0000-000000000405"), "DEVELOPER", true),
            "reporter-token", new Actor(UUID.fromString("00000000-0000-0000-0000-000000000403"), "REPORTER", true),
            "maintainer-token", new Actor(UUID.fromString("00000000-0000-0000-0000-000000000406"), "MAINTAINER", true),
            "inactive-token", new Actor(UUID.fromString("00000000-0000-0000-0000-000000000404"), "DEVELOPER", false));

    public static void main(String[] args) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__test/session", exchange -> {
            try (exchange) {
                Actor actor = actor(exchange.getRequestHeaders().getFirst("Authorization"));
                if (actor == null) {
                    send(exchange, 401, "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication required\",\"requestId\":\"test-session\"}");
                } else {
                    send(exchange, 200, "{\"id\":\"" + actor.id() + "\",\"role\":\"" + actor.role()
                            + "\",\"active\":" + actor.active() + "}");
                }
            }
        });
        server.createContext("/", exchange -> {
            try (exchange) {
                Actor actor = actor(exchange.getRequestHeaders().getFirst("Authorization"));
                if (actor == null) {
                    send(exchange, 401, "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication required\",\"requestId\":\"test-request\"}");
                } else if (!actor.active() || !actor.role().equals("DEVELOPER")) {
                    send(exchange, 403, "{\"code\":\"FORBIDDEN\",\"message\":\"Active Developer access required\",\"requestId\":\"test-request\"}");
                } else {
                    send(exchange, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"No application handler mounted\",\"requestId\":\"test-request\"}");
                }
            }
        });
        server.start();
        System.out.println("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static Actor actor(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? SESSIONS.get(authorization.substring("Bearer ".length())) : null;
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
