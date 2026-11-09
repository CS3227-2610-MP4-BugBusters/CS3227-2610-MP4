package mp4.developer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import mp4.developer.ChecklistDraftStore.Actor;
import mp4.developer.ChecklistDraftStore.Outcome;

/** HTTP adapter for private checklist Save; authentication supplies the trusted actor. */
public final class ChecklistDraftHttpHandler {
    private static final String PATH_PREFIX = "/api/reports/";
    private static final String PATH_SUFFIX = "/checklist/draft";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Set<String> REQUEST_FIELDS = Set.of("steps", "expectedDraftRevision");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ChecklistDraftStore store;

    public ChecklistDraftHttpHandler(ChecklistDraftStore store) {
        this.store = store;
    }

    public void handle(HttpExchange exchange, Actor actor) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(PATH_PREFIX) || !path.endsWith(PATH_SUFFIX)) {
            sendError(exchange, 404, "NOT_FOUND", "Resource not found");
            return;
        }
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("PUT")) {
            exchange.getResponseHeaders().set("Allow", "GET, PUT");
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
            return;
        }

        UUID reportId;
        try {
            int idStart = PATH_PREFIX.length();
            int idEnd = path.length() - PATH_SUFFIX.length();
            if (idStart >= idEnd) {
                throw new IllegalArgumentException("Invalid report ID");
            }
            String id = path.substring(idStart, idEnd);
            if (id.contains("/")) {
                throw new IllegalArgumentException("Invalid report ID");
            }
            reportId = UUID.fromString(id);
        } catch (IllegalArgumentException invalidId) {
            sendError(exchange, 400, "INVALID_REQUEST", "Invalid report ID");
            return;
        }

        if (method.equals("GET")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            try {
                var result = store.read(actor, reportId);
                if (result == null) {
                    sendError(exchange, 403, "FORBIDDEN", "Access denied");
                } else {
                    var body = JSON.createObjectNode();
                    body.set("steps", JSON.valueToTree(result.steps()));
                    body.put("draftRevision", result.draftRevision());
                    sendJson(exchange, 200, body);
                }
            } catch (SQLException | RuntimeException failure) {
                sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
            }
            return;
        }

        byte[] bytes;
        try {
            bytes = readBounded(exchange);
        } catch (BodyTooLarge | IOException invalidBody) {
            sendError(exchange, 400, "INVALID_REQUEST", "Invalid request body");
            return;
        }

        SaveRequest request;
        try {
            request = parseRequest(bytes);
        } catch (IOException | IllegalArgumentException invalidBody) {
            sendError(exchange, 400, "INVALID_REQUEST", "Invalid request body");
            return;
        }

        try {
            var result = store.save(actor, reportId, request.expectedDraftRevision(), request.steps());
            if (result.outcome() == Outcome.SAVED) {
                sendJson(exchange, 200, JSON.createObjectNode().put("draftRevision", result.draftRevision()));
            } else if (result.outcome() == Outcome.STALE_VERSION) {
                sendError(exchange, 409, "STALE_VERSION", "Draft revision is out of date");
            } else if (result.outcome() == Outcome.FORBIDDEN) {
                sendError(exchange, 403, "FORBIDDEN", "Access denied");
            } else {
                sendError(exchange, 409, "INVALID_STATE", "Checklist cannot be saved in this report state");
            }
        } catch (SQLException failure) {
            sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
        }
    }

    private static SaveRequest parseRequest(byte[] bytes) throws IOException {
        JsonNode body = JSON.readTree(bytes);
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Invalid request body");
        }
        ObjectNode object = (ObjectNode) body;
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!REQUEST_FIELDS.contains(fields.next())) {
                throw new IllegalArgumentException("Invalid request body");
            }
        }
        JsonNode stepsNode = object.get("steps");
        JsonNode revisionNode = object.get("expectedDraftRevision");
        if (stepsNode == null || !stepsNode.isArray() || revisionNode == null
                || !revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong()
                || revisionNode.longValue() < 0) {
            throw new IllegalArgumentException("Invalid request body");
        }
        var steps = new ArrayList<String>();
        var utf8 = StandardCharsets.UTF_8.newEncoder();
        for (JsonNode step : stepsNode) {
            if (!step.isTextual()) {
                throw new IllegalArgumentException("Invalid request body");
            }
            String value = step.textValue();
            if (value.indexOf('\0') >= 0 || !utf8.canEncode(value)) {
                throw new IllegalArgumentException("Invalid request body");
            }
            steps.add(value);
        }
        return new SaveRequest(revisionNode.longValue(), steps);
    }

    private static byte[] readBounded(HttpExchange exchange) throws IOException, BodyTooLarge {
        try (var input = exchange.getRequestBody(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BODY_BYTES) {
                    throw new BodyTooLarge();
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        var error = JSON.createObjectNode()
                .put("code", code)
                .put("message", message)
                .put("requestId", UUID.randomUUID().toString());
        sendJson(exchange, status, error);
    }

    private static void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class BodyTooLarge extends Exception {}

    private record SaveRequest(long expectedDraftRevision, java.util.List<String> steps) {}
}
