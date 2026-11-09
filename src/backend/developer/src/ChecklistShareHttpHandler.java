package mp4.developer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import mp4.developer.ChecklistDraftStore.Actor;
import mp4.developer.ChecklistShareService.Outcome;

/** Local HTTP adapter for Share; the host supplies the actor from trusted authentication. */
public final class ChecklistShareHttpHandler {
    private static final String PATH_PREFIX = "/api/reports/";
    private static final String PATH_SUFFIX = "/checklist/share";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Set<String> REQUEST_FIELDS = Set.of("expectedDraftRevision", "expectedReportVersion");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ChecklistShareService service;

    public ChecklistShareHttpHandler(ChecklistShareService service) {
        this.service = service;
    }

    public void handle(HttpExchange exchange, Actor actor) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(PATH_PREFIX) || !path.endsWith(PATH_SUFFIX)) {
            sendError(exchange, 404, "NOT_FOUND", "Resource not found");
            return;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
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
            if (!reportId.toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("Invalid report ID");
            }
        } catch (IllegalArgumentException invalidId) {
            sendError(exchange, 400, "INVALID_REQUEST", "Invalid report ID");
            return;
        }

        byte[] bytes;
        try {
            bytes = readBounded(exchange);
        } catch (BodyTooLarge | IOException invalidBody) {
            sendError(exchange, 400, "INVALID_REQUEST", "Invalid request body");
            return;
        }

        ShareRequest request;
        try {
            request = parseRequest(bytes);
        } catch (IOException | IllegalArgumentException invalidBody) {
            sendError(exchange, 400, "INVALID_REQUEST", "Invalid request body");
            return;
        }
        if (actor == null) {
            sendError(exchange, 403, "FORBIDDEN", "Access denied");
            return;
        }

        try {
            var result = service.share(new ChecklistShareService.ShareRequest(
                    actor, reportId, request.expectedDraftRevision(), request.expectedReportVersion()));
            if (result == null || result.outcome() == null) {
                sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
                return;
            }
            switch (result.outcome()) {
                case SHARED -> sendSuccess(exchange, result.reportVersion(), true);
                case UNCHANGED -> sendSuccess(exchange, result.reportVersion(), false);
                case FORBIDDEN -> sendError(exchange, 403, "FORBIDDEN", "Access denied");
                case STALE_VERSION -> sendError(exchange, 409, "STALE_VERSION", "Checklist version is out of date");
                case INVALID_STATE -> sendError(exchange, 409, "INVALID_STATE", "Checklist cannot be shared in this report state");
                case EMPTY_DRAFT -> sendError(exchange, 409, "EMPTY_DRAFT", "Add at least one step before sharing");
                case VERSION_LIMIT -> sendError(exchange, 409, "VERSION_LIMIT", "Report version cannot be safely incremented");
            }
        } catch (SQLException | RuntimeException failure) {
            sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
        }
    }

    private static ShareRequest parseRequest(byte[] bytes) throws IOException {
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
        JsonNode draftRevision = object.get("expectedDraftRevision");
        JsonNode reportVersion = object.get("expectedReportVersion");
        if (!isSafeInteger(draftRevision) || !isSafeInteger(reportVersion)) {
            throw new IllegalArgumentException("Invalid request body");
        }
        return new ShareRequest(draftRevision.longValue(), reportVersion.longValue());
    }

    private static boolean isSafeInteger(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 0 && value.longValue() <= ChecklistShareService.MAX_SAFE_VERSION;
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

    private static void sendSuccess(HttpExchange exchange, Long reportVersion, boolean changed) throws IOException {
        if (reportVersion == null || reportVersion < 0
                || reportVersion > ChecklistShareService.MAX_SAFE_VERSION) {
            sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
            return;
        }
        var body = JSON.createObjectNode().put("reportVersion", reportVersion).put("changed", changed);
        sendJson(exchange, 200, body);
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
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class BodyTooLarge extends Exception {}

    private record ShareRequest(long expectedDraftRevision, long expectedReportVersion) {}
}
