package mp4.developer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import mp4.developer.ChecklistDraftStore.Actor;
import mp4.developer.ChecklistDraftStore.Role;

/** Local HTTP adapter for the shared-only checklist projection. */
public final class ChecklistSharedHttpHandler {
    private static final String PATH_PREFIX = "/api/reports/";
    private static final String PATH_SUFFIX = "/checklist/shared";
    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface
    public interface MaintainerAccess {
        boolean canView(Actor actor, UUID reportId) throws SQLException;
    }

    private final DataSource database;
    private final MaintainerAccess maintainerAccess;

    public ChecklistSharedHttpHandler(DataSource database, MaintainerAccess maintainerAccess) {
        this.database = database;
        this.maintainerAccess = maintainerAccess;
    }

    public void handle(HttpExchange exchange, Actor actor) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(PATH_PREFIX) || !path.endsWith(PATH_SUFFIX)) {
            sendError(exchange, 404, "NOT_FOUND", "Resource not found");
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
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

        if (actor == null || !actor.active() || actor.id() == null || actor.role() == null) {
            sendForbidden(exchange);
            return;
        }

        try {
            List<String> steps = readForAuthorizedActor(actor, reportId);
            if (steps == null) {
                sendForbidden(exchange);
            } else {
                sendJson(exchange, 200, JSON.createObjectNode().set("steps", JSON.valueToTree(steps)));
            }
        } catch (SQLException | RuntimeException failure) {
            sendError(exchange, 500, "INTERNAL_ERROR", "Request could not be completed");
        }
    }

    private List<String> readForAuthorizedActor(Actor actor, UUID reportId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                UUID reporterId;
                UUID assigneeId;
                try (var report = connection.prepareStatement(
                        "SELECT reporter_id, assignee_id FROM reports WHERE id = ? FOR UPDATE")) {
                    report.setObject(1, reportId);
                    try (var row = report.executeQuery()) {
                        if (!row.next()) {
                            connection.commit();
                            return null;
                        }
                        reporterId = row.getObject("reporter_id", UUID.class);
                        assigneeId = row.getObject("assignee_id", UUID.class);
                    }
                }

                boolean authorized = switch (actor.role()) {
                    case REPORTER -> actor.id().equals(reporterId);
                    case DEVELOPER -> actor.id().equals(assigneeId);
                    case MAINTAINER -> maintainerAccess != null && maintainerAccess.canView(actor, reportId);
                };
                if (!authorized) {
                    connection.commit();
                    return null;
                }

                List<String> steps = List.of();
                try (var checklist = connection.prepareStatement(
                        "SELECT shared_steps FROM investigation_checklists WHERE report_id = ?")) {
                    checklist.setObject(1, reportId);
                    try (var row = checklist.executeQuery()) {
                        if (row.next()) {
                            var shared = row.getArray("shared_steps");
                            try {
                                steps = List.of((String[]) shared.getArray());
                            } finally {
                                shared.free();
                            }
                        }
                    }
                }
                connection.commit();
                return steps;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private static void sendForbidden(HttpExchange exchange) throws IOException {
        sendError(exchange, 403, "FORBIDDEN", "Access denied");
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
}
