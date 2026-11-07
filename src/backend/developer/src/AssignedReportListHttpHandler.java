package mp4.developer;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;
import mp4.developer.AssignedReportStore.Page;

/** HTTP adapter for the first, unfiltered assigned-report list page. */
public final class AssignedReportListHttpHandler {
    private static final String PATH = "/api/developer/reports";
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
        if (hasUnsupportedQuery(exchange.getRequestURI().getRawQuery())) {
            sendError(exchange, 400, "VALIDATION_FAILED", "Invalid query parameters");
            return;
        }
        try {
            Page page = store.firstPage(actor.id());
            sendJson(exchange, 200, page);
        } catch (SQLException failure) {
            sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
        }
    }

    private static boolean hasUnsupportedQuery(String query) {
        if (query == null || query.isEmpty()) return false;
        return true;
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
