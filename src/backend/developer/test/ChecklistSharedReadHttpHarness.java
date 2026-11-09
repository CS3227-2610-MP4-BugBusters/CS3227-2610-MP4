package mp4.developer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import mp4.developer.ChecklistDraftStore.Actor;
import mp4.developer.ChecklistDraftStore.Role;

/** Isolated loopback harness with synthetic Developer, Reporter and Maintainer identities. */
public final class ChecklistSharedReadHttpHarness {
    @FunctionalInterface
    interface AuthenticatedHandler {
        void handle(HttpExchange exchange, Actor actor) throws IOException;
    }

    public static void main(String[] args) throws IOException {
        start(null);
    }

    static HttpServer start(AuthenticatedHandler application) throws IOException {
        var developer = actor("MP4_TEST_DEVELOPER_ID", Role.DEVELOPER, true);
        var unassigned = actor("MP4_TEST_UNASSIGNED_ID", Role.DEVELOPER, true);
        var reporter = actor("MP4_TEST_REPORTER_ID", Role.REPORTER, true);
        var otherReporter = actor("MP4_TEST_OTHER_REPORTER_ID", Role.REPORTER, true);
        var maintainer = actor("MP4_TEST_MAINTAINER_ID", Role.MAINTAINER, true);
        var inactiveReporter = actor("MP4_TEST_INACTIVE_REPORTER_ID", Role.REPORTER, false);
        var inactiveMaintainer = actor("MP4_TEST_INACTIVE_MAINTAINER_ID", Role.MAINTAINER, false);
        Map<String, Actor> sessions = Map.of(
                token("MP4_TEST_SESSION_TOKEN"), developer,
                token("MP4_TEST_UNASSIGNED_SESSION_TOKEN"), unassigned,
                token("MP4_TEST_REPORTER_SESSION_TOKEN"), reporter,
                token("MP4_TEST_OTHER_REPORTER_SESSION_TOKEN"), otherReporter,
                token("MP4_TEST_MAINTAINER_SESSION_TOKEN"), maintainer,
                token("MP4_TEST_INACTIVE_REPORTER_SESSION_TOKEN"), inactiveReporter,
                token("MP4_TEST_INACTIVE_MAINTAINER_SESSION_TOKEN"), inactiveMaintainer);

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                Actor actor = sessions.get(exchange.getRequestHeaders().getFirst("Authorization"));
                if (actor == null) {
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

    private static Actor actor(String idEnv, Role role, boolean active) {
        return new Actor(UUID.fromString(requiredEnv(idEnv)), role, active);
    }

    private static String token(String tokenEnv) {
        return "Bearer " + requiredEnv(tokenEnv);
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
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
