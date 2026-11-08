package mp4.developer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.postgresql.ds.PGSimpleDataSource;
import mp4.developer.ChecklistDraftStore.Actor;
import mp4.developer.ChecklistDraftStore.Role;

/** Repeatable synthetic-session authorization and PostgreSQL lifecycle race checks. */
public final class PrivateNotesAuthorizationRaceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String APP_NAME = "private_notes_race_test";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public static void main(String[] args) throws Exception {
        var database = new PGSimpleDataSource();
        database.setURL(requiredEnv("MP4_TEST_JDBC_URL"));
        database.setApplicationName(APP_NAME);
        var actors = actors();
        var handler = new PrivateNotesHttpHandler(new JdbcPrivateNotesService(database));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                Actor actor = actors.get(exchange.getRequestHeaders().getFirst("Authorization"));
                if (actor == null) {
                    exchange.sendResponseHeaders(401, -1);
                } else {
                    handler.handle(exchange, actor);
                }
            }
        });
        server.start();
        ExecutorService requests = Executors.newCachedThreadPool();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            UUID assignee = actorId("current");
            UUID newAssignee = actorId("new");
            testDeniedActors(database, origin, assignee);
            testNotesFirstThenReassignment(database, origin, assignee, newAssignee);
            testReassignmentFirst(database, origin, requests, assignee, newAssignee);
            testNotesFirstThenClosure(database, origin, assignee);
            testClosureFirst(database, origin, requests, assignee);
            System.out.println("Private-notes synthetic API authorization and reassignment/closure ordering passed");
        } finally {
            requests.shutdownNow();
            server.stop(0);
        }
    }

    private static Map<String, Actor> actors() {
        var actors = new LinkedHashMap<String, Actor>();
        actors.put("Bearer reporter", new Actor(actorId("reporter"), Role.REPORTER, true));
        actors.put("Bearer maintainer", new Actor(actorId("maintainer"), Role.MAINTAINER, true));
        actors.put("Bearer unrelated", new Actor(actorId("unrelated"), Role.DEVELOPER, true));
        actors.put("Bearer current", new Actor(actorId("current"), Role.DEVELOPER, true));
        actors.put("Bearer former", new Actor(actorId("former"), Role.DEVELOPER, true));
        actors.put("Bearer new", new Actor(actorId("new"), Role.DEVELOPER, true));
        return actors;
    }

    private static void testDeniedActors(PGSimpleDataSource database, String origin, UUID assignee)
            throws Exception {
        UUID report = createReport(database, assignee, "RESOLUTION_PROPOSED");
        save(origin, "current", report, "sensitive note", 0, 200);
        String before = snapshot(database, report);
        for (String token : new String[] {"reporter", "maintainer", "unrelated", "former", "new"}) {
            Response read = send(origin, token, "GET", report, null, 0);
            require(read.status() == 403 && !read.body().has("text") && !read.body().has("notesRevision"),
                    token + " read was not denied without protected fields: " + read);
            Response write = send(origin, token, "PUT", report, "intrusion", 1);
            require(write.status() == 403 && !write.body().has("text") && !write.body().has("notesRevision"),
                    token + " write was not denied without protected fields: " + write);
            require(snapshot(database, report).equals(before), token + " denial changed stored state");
        }
    }

    private static void testNotesFirstThenReassignment(PGSimpleDataSource database, String origin,
            UUID assignee, UUID newAssignee) throws Exception {
        UUID report = createReport(database, assignee, "RESOLUTION_PROPOSED");
        save(origin, "current", report, "saved before reassignment", 0, 200);
        lifecycleUpdate(database, report, "assignee_id", newAssignee);
        require(send(origin, "current", "GET", report, null, 0).status() == 403,
                "former assignee retained access after reassignment");
        Response formerWrite = send(origin, "current", "PUT", report, "overwrite", 1);
        require(formerWrite.status() == 403 && !formerWrite.body().has("text")
                && !formerWrite.body().has("notesRevision"), "former assignee write was not safely denied");
        Response newRead = send(origin, "new", "GET", report, null, 0);
        require(newRead.status() == 200 && "saved before reassignment".equals(newRead.body().path("text").asText())
                && newRead.body().path("notesRevision").asLong() == 1, "new assignee did not see preserved notes");
    }

    private static void testReassignmentFirst(PGSimpleDataSource database, String origin,
            ExecutorService requests, UUID assignee, UUID newAssignee) throws Exception {
        UUID report = createReport(database, assignee, "RESOLUTION_PROPOSED");
        String before = snapshot(database, report);
        try (Connection lifecycle = database.getConnection()) {
            lifecycle.setAutoCommit(false);
            update(lifecycle, report, "assignee_id", newAssignee);
            CompletableFuture<Response> pending = CompletableFuture.supplyAsync(
                    () -> uncheckedSend(origin, "current", "PUT", report, "must be denied", 0), requests);
            awaitNotesWriteLock(database, pending);
            lifecycle.commit();
            Response response = pending.get(5, TimeUnit.SECONDS);
            require(response.status() == 403, "writer losing reassignment race was not forbidden: " + response);
            String after = snapshot(database, report);
            require(notesPart(after).equals(notesPart(before)), "denied reassignment race changed notes");
            require(proposalPart(after).equals(proposalPart(before)), "denied reassignment race changed proposal");
        }
    }

    private static void testNotesFirstThenClosure(PGSimpleDataSource database, String origin, UUID assignee)
            throws Exception {
        UUID report = createReport(database, assignee, "RESOLUTION_PROPOSED");
        save(origin, "current", report, "saved before closure", 0, 200);
        String beforeClose = snapshot(database, report);
        lifecycleUpdate(database, report, "status", "CLOSED");
        String afterClose = snapshot(database, report);
        require(notesPart(afterClose).equals(notesPart(beforeClose)), "closure changed notes");
        require(proposalPart(afterClose).equals(proposalPart(beforeClose)), "closure changed pending proposal");
        Response denied = send(origin, "current", "PUT", report, "after closure", 1);
        require(denied.status() == 409 && "INVALID_STATE".equals(denied.body().path("code").asText()),
                "closed report write was not rejected by state: " + denied);
        require(snapshot(database, report).equals(afterClose), "post-closure denial changed stored state");
    }

    private static void testClosureFirst(PGSimpleDataSource database, String origin,
            ExecutorService requests, UUID assignee) throws Exception {
        UUID report = createReport(database, assignee, "RESOLUTION_PROPOSED");
        String before = snapshot(database, report);
        try (Connection lifecycle = database.getConnection()) {
            lifecycle.setAutoCommit(false);
            update(lifecycle, report, "status", "CLOSED");
            CompletableFuture<Response> pending = CompletableFuture.supplyAsync(
                    () -> uncheckedSend(origin, "current", "PUT", report, "must be denied", 0), requests);
            awaitNotesWriteLock(database, pending);
            lifecycle.commit();
            Response response = pending.get(5, TimeUnit.SECONDS);
            require(response.status() == 409 && "INVALID_STATE".equals(response.body().path("code").asText()),
                    "writer losing closure race was not rejected by state: " + response);
            String after = snapshot(database, report);
            require(notesPart(after).equals(notesPart(before)), "denied closure race changed notes");
            require(proposalPart(after).equals(proposalPart(before)), "denied closure race changed proposal");
        }
    }

    private static void awaitNotesWriteLock(PGSimpleDataSource database, CompletableFuture<?> pending)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !pending.isDone()) {
            try (Connection connection = database.getConnection();
                 PreparedStatement query = connection.prepareStatement(
                         "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? "
                                 + "AND wait_event_type = 'Lock')")) {
                query.setString(1, APP_NAME);
                try (var result = query.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("notes HTTP write did not block behind the lifecycle report lock");
    }

    private static UUID createReport(PGSimpleDataSource database, UUID assignee, String status) throws Exception {
        UUID report = UUID.randomUUID();
        UUID reporter = actorId("reporter");
        UUID proposal = UUID.randomUUID();
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "INSERT INTO reports (id, reporter_id, assignee_id, status, version) VALUES (?, ?, ?, ?, 7)")) {
                statement.setObject(1, report);
                statement.setObject(2, reporter);
                statement.setObject(3, assignee);
                statement.setString(4, status);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO resolution_proposals (id, report_id, status, version, contents) "
                            + "VALUES (?, ?, 'PENDING', 1, 'Preserve this proposal')")) {
                statement.setObject(1, proposal);
                statement.setObject(2, report);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE reports SET current_proposal_id = ? WHERE id = ?")) {
                statement.setObject(1, proposal);
                statement.setObject(2, report);
                statement.executeUpdate();
            }
            connection.commit();
        }
        return report;
    }

    private static void lifecycleUpdate(PGSimpleDataSource database, UUID report, String column, Object value)
            throws Exception {
        try (Connection connection = database.getConnection()) {
            update(connection, report, column, value);
        }
    }

    private static void update(Connection connection, UUID report, String column, Object value) throws Exception {
        if (!column.equals("assignee_id") && !column.equals("status")) throw new IllegalArgumentException(column);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE reports SET " + column + " = ? WHERE id = ?")) {
            statement.setObject(1, value);
            statement.setObject(2, report);
            statement.executeUpdate();
        }
    }

    private static String snapshot(PGSimpleDataSource database, UUID report) throws Exception {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT jsonb_build_object(
                       'notes', COALESCE((SELECT jsonb_build_object('text', body, 'revision', notes_revision)
                         FROM developer_private_notes WHERE report_id = reports.id),
                         jsonb_build_object('text', '', 'revision', 0)),
                       'report', jsonb_build_object('assigneeId', assignee_id, 'status', status,
                         'version', version, 'currentProposalId', current_proposal_id),
                       'proposal', (SELECT jsonb_build_object('status', status, 'version', version, 'contents', contents)
                         FROM resolution_proposals WHERE id = reports.current_proposal_id))
                     FROM reports WHERE id = ?
                     """)) {
            statement.setObject(1, report);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new AssertionError("fixture report missing");
                return result.getString(1);
            }
        }
    }

    private static String notesPart(String snapshot) throws Exception {
        return JSON.readTree(snapshot).path("notes").toString();
    }

    private static String proposalPart(String snapshot) throws Exception {
        return JSON.readTree(snapshot).path("proposal").toString();
    }

    private static void save(String origin, String token, UUID report, String text, long revision, int expected)
            throws Exception {
        Response response = send(origin, token, "PUT", report, text, revision);
        require(response.status() == expected, "unexpected save response: " + response);
    }

    private static Response send(String origin, String token, String method, UUID report, String text, long revision)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + "/api/reports/" + report
                + "/private-notes")).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token);
        if (method.equals("GET")) builder.GET();
        else builder.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(
                JSON.createObjectNode().put("text", text).put("expectedNotesRevision", revision).toString()));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), JSON.readTree(response.body()));
    }

    private static Response uncheckedSend(String origin, String token, String method, UUID report,
            String text, long revision) {
        try { return send(origin, token, method, report, text, revision); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private static UUID actorId(String name) {
        return UUID.nameUUIDFromBytes(("private-notes-test-" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Response(int status, JsonNode body) {}
}
