package mp4.developer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import mp4.developer.ChecklistDraftStore.Actor;

/** Local HTTP adapter; production routing must pass the actor from trusted session authentication. */
public final class PrivateNotesHttpHandler {
    private static final String PREFIX = "/api/reports/";
    private static final String SUFFIX = "/private-notes";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_TEXT_BYTES = 16 * 1024;
    private static final long MAX_SAFE_REVISION = 9_007_199_254_740_991L;
    private static final Set<String> FIELDS = Set.of("text", "expectedNotesRevision");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final PrivateNotesService service;

    public PrivateNotesHttpHandler(PrivateNotesService service) {
        this.service = service;
    }

    public void handle(HttpExchange exchange, Actor actor) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) {
            sendError(exchange, 404, "NOT_FOUND", "Resource not found");
            return;
        }
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("PUT")) {
            exchange.getResponseHeaders().set("Allow", "GET, PUT");
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
            return;
        }
        if (actor == null) {
            sendError(exchange, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }

        UUID reportId;
        try {
            int start = PREFIX.length();
            int end = path.length() - SUFFIX.length();
            if (start >= end) throw new IllegalArgumentException();
            String id = path.substring(start, end);
            if (id.contains("/")) throw new IllegalArgumentException();
            reportId = UUID.fromString(id);
            if (!reportId.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalidId) {
            sendError(exchange, 400, "INVALID_REQUEST", "Invalid report ID");
            return;
        }

        if (method.equals("GET")) {
            try {
                PrivateNotesService.Note note = service.read(actor, reportId);
                sendJson(exchange, 200, JSON.createObjectNode().put("text", note.text())
                        .put("notesRevision", note.notesRevision()));
            } catch (PrivateNotesService.AccessDeniedException denied) {
                sendError(exchange, 403, "FORBIDDEN", "Access denied");
            } catch (SQLException failure) {
                sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
            }
            return;
        }

        if (!isJson(exchange)) {
            sendError(exchange, 400, "INVALID_REQUEST", "Invalid request body");
            return;
        }
        byte[] bytes;
        try {
            bytes = readBounded(exchange);
        } catch (BodyTooLarge tooLarge) {
            sendError(exchange, 413, "PAYLOAD_TOO_LARGE", "Request body is too large");
            return;
        } catch (IOException invalidBody) {
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
            var result = service.replace(actor, reportId, request.text(), request.expectedNotesRevision());
            sendJson(exchange, 200, JSON.createObjectNode().put("notesRevision", result.notesRevision()));
        } catch (PrivateNotesService.AccessDeniedException denied) {
            sendError(exchange, 403, "FORBIDDEN", "Access denied");
        } catch (PrivateNotesService.InvalidStateException invalidState) {
            sendError(exchange, 409, "INVALID_STATE", "Notes cannot be changed in this report state");
        } catch (PrivateNotesService.StaleRevisionException stale) {
            sendError(exchange, 409, "STALE_VERSION", "Notes revision is out of date");
        } catch (PrivateNotesService.RevisionLimitException limit) {
            sendError(exchange, 409, "REVISION_LIMIT", "Notes revision cannot be increased");
        } catch (SQLException failure) {
            sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
        }
    }

    private static SaveRequest parseRequest(byte[] bytes) throws IOException {
        String utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        JsonNode body = JSON.readTree(utf8);
        if (body == null || !body.isObject()) throw new IllegalArgumentException();
        ObjectNode object = (ObjectNode) body;
        var fields = object.fieldNames();
        while (fields.hasNext()) if (!FIELDS.contains(fields.next())) throw new IllegalArgumentException();
        JsonNode textNode = object.get("text");
        JsonNode revisionNode = object.get("expectedNotesRevision");
        if (textNode == null || !textNode.isTextual() || revisionNode == null
                || !revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong()) {
            throw new IllegalArgumentException();
        }
        long revision = revisionNode.longValue();
        String text = textNode.textValue();
        var encoder = StandardCharsets.UTF_8.newEncoder();
        if (revision < 0 || revision > MAX_SAFE_REVISION || text.indexOf('\0') >= 0
                || !encoder.canEncode(text) || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException();
        }
        return new SaveRequest(text, revision);
    }

    private static boolean isJson(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        return contentType != null && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }

    private static byte[] readBounded(HttpExchange exchange) throws IOException, BodyTooLarge {
        try (var input = exchange.getRequestBody(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BODY_BYTES) throw new BodyTooLarge();
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        sendJson(exchange, status, JSON.createObjectNode().put("code", code).put("message", message)
                .put("requestId", UUID.randomUUID().toString()));
    }

    private static void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class BodyTooLarge extends Exception {}
    private record SaveRequest(String text, long expectedNotesRevision) {}
}
