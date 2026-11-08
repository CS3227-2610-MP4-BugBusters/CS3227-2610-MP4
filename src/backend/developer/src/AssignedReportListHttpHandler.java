package mp4.developer;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mp4.developer.AssignedReportStore.Page;

/** HTTP adapter for the first, unfiltered assigned-report list page. */
public final class AssignedReportListHttpHandler {
    private static final String PATH = "/api/developer/reports";
    private static final Set<String> STATUSES = Set.of(
            "SUBMITTED", "ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED", "CLOSED");
    private final AssignedReportStore store;

    public AssignedReportListHttpHandler(AssignedReportStore store) {
        this.store = store;
    }

    public void handle(HttpExchange exchange, ChecklistDraftStore.Actor actor) throws IOException {
        if (!exchange.getRequestURI().getPath().equals(PATH)) {
            sendError(exchange, 404, "NOT_FOUND", "Resource not found");
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
            return;
        }
        if (actor == null) {
            sendError(exchange, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        if (!actor.active() || actor.role() != ChecklistDraftStore.Role.DEVELOPER) {
            sendError(exchange, 403, "FORBIDDEN", "Active Developer access required");
            return;
        }
        QueryFilters filters;
        try {
            filters = parseFilters(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException invalidQuery) {
            sendError(exchange, 400, "VALIDATION_FAILED", "Invalid query parameters");
            return;
        }
        try {
            Page page = store.searchPage(actor.id(), filters.search(), filters.status());
            sendJson(exchange, 200, page);
        } catch (SQLException failure) {
            sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
        }
    }

    private static QueryFilters parseFilters(String query) {
        if (query == null || query.isEmpty()) return new QueryFilters(null, null);
        Map<String, String> parameters = new HashMap<>();
        for (String part : query.split("&", -1)) {
            int separator = part.indexOf('=');
            String key = decodeQueryComponent(separator < 0 ? part : part.substring(0, separator));
            String value = decodeQueryComponent(separator < 0 ? "" : part.substring(separator + 1));
            if (key.indexOf('\0') >= 0 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("NUL is not allowed in query parameters");
            }
            if ((!key.equals("search") && !key.equals("status"))
                    || parameters.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Unsupported or repeated query parameter");
            }
        }
        String search = parameters.get("search");
        if (search != null) {
            search = trimWhitespace(search);
            if (search.isEmpty()) search = null;
        }
        String status = parameters.get("status");
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported status");
        }
        return new QueryFilters(search, status);
    }

    private record QueryFilters(String search, String status) {}

    private static String decodeQueryComponent(String component) {
        var bytes = new ByteArrayOutputStream();
        for (int i = 0; i < component.length();) {
            char current = component.charAt(i);
            if (current == '%') {
                if (i + 2 >= component.length()) throw new IllegalArgumentException("Invalid escape");
                int high = hexDigit(component.charAt(i + 1));
                int low = hexDigit(component.charAt(i + 2));
                if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid escape");
                bytes.write((high << 4) | low);
                i += 3;
            } else if (current == '+') {
                bytes.write(' ');
                i++;
            } else {
                int codePoint = component.codePointAt(i);
                if (Character.isSurrogate(current) && Character.charCount(codePoint) == 1) {
                    throw new IllegalArgumentException("Invalid Unicode character");
                }
                byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(encoded);
                i += Character.charCount(codePoint);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IllegalArgumentException("Invalid UTF-8 query parameter", malformedUtf8);
        }
    }

    private static int hexDigit(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }

    private static String trimWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        sendJson(exchange, status, new Error(code, message, UUID.randomUUID().toString()));
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String json(Object body) {
        if (body instanceof Error error) {
            return "{\"code\":" + quote(error.code()) + ",\"message\":" + quote(error.message())
                    + ",\"requestId\":" + quote(error.requestId()) + "}";
        }
        Page page = (Page) body;
        var result = new StringBuilder("{\"items\":[");
        boolean first = true;
        for (var item : page.items()) {
            if (!first) result.append(',');
            first = false;
            result.append("{\"id\":").append(quote(item.id().toString()))
                    .append(",\"title\":").append(quote(item.title()))
                    .append(",\"status\":").append(quote(item.status())).append('}');
        }
        return result.append("],\"page\":").append(page.page())
                .append(",\"pageSize\":").append(page.pageSize())
                .append(",\"totalItems\":").append(page.totalItems()).append('}').toString();
    }

    private static String quote(String value) {
        var result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    private record Error(String code, String message, String requestId) {}
}
