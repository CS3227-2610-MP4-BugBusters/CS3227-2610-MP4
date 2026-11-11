package mp4.platform;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class DeveloperReportController {
    private final DataSource dataSource;
    private final DataSource actorDataSource;
    DeveloperReportController(DataSource dataSource) {
        this.dataSource = dataSource;
        this.actorDataSource = new ActorTransactionDataSource(dataSource);
    }

    @GetMapping("/api/developer/notifications")
    Object notifications(HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        AuthProvider.CurrentUser user = currentUser();
        try (var connection = actorDataSource.getConnection()) {
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("""
                    SELECT n.id, n.event_type, n.created_at, n.read_at,
                           CASE WHEN r.assignee_id = n.recipient_id THEN n.report_id ELSE NULL END AS visible_report_id
                    FROM public.developer_notifications n
                    JOIN public.reports r ON r.id = n.report_id
                    WHERE n.recipient_id = ?
                    ORDER BY n.created_at DESC, n.id DESC
                    LIMIT 50
                    """)) {
                query.setObject(1, user.id());
                java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> item = new java.util.LinkedHashMap<>();
                        item.put("id", rows.getObject("id").toString());
                        item.put("type", rows.getString("event_type"));
                        item.put("createdAt", rows.getTimestamp("created_at").toInstant().toString());
                        item.put("read", rows.getTimestamp("read_at") != null);
                        var visibleReport = rows.getObject("visible_report_id", UUID.class);
                        item.put("reportId", visibleReport == null ? null : visibleReport.toString());
                        items.add(item);
                    }
                }
                connection.commit();
                return Map.of("items", items);
            } catch (java.sql.SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (ActorTransactionDataSource.Revoked revoked) {
            AuthHttp.error(response, 401, "UNAUTHENTICATED", "Authentication required");
            return null;
        } catch (java.sql.SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Notifications are temporarily unavailable");
            return null;
        }
    }

    @PostMapping("/api/developer/notifications/{notificationId}/read")
    void markNotificationRead(@PathVariable("notificationId") String notificationId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        if (request.getQueryString() != null || request.getInputStream().readNBytes(1).length != 0) {
            AuthHttp.error(response, 400, "INVALID_REQUEST", "Invalid notification request");
            return;
        }
        final UUID id;
        try { id = UUID.fromString(notificationId); }
        catch (IllegalArgumentException invalid) {
            AuthHttp.error(response, 404, "NOT_FOUND", "Notification not found");
            return;
        }
        AuthProvider.CurrentUser user = currentUser();
        try (var connection = actorDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("""
                    UPDATE public.developer_notifications SET read_at = COALESCE(read_at, now())
                    WHERE id = ? AND recipient_id = ?
                    """)) {
                update.setObject(1, id); update.setObject(2, user.id());
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    AuthHttp.error(response, 404, "NOT_FOUND", "Notification not found");
                    return;
                }
                connection.commit();
                response.setStatus(204);
            } catch (java.sql.SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (ActorTransactionDataSource.Revoked revoked) {
            AuthHttp.error(response, 401, "UNAUTHENTICATED", "Authentication required");
        } catch (java.sql.SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Notifications are temporarily unavailable");
        }
    }

    private static AuthProvider.CurrentUser currentUser() {
        return (AuthProvider.CurrentUser) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
    }

    @GetMapping("/api/developer/reports/{reportId}")
    Object detail(@PathVariable("reportId") String reportId, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        final UUID id;
        try { id = UUID.fromString(reportId); }
        catch (IllegalArgumentException invalid) {
            AuthHttp.error(response, 404, "NOT_FOUND", "Report not found");
            return null;
        }
        AuthProvider.CurrentUser user = (AuthProvider.CurrentUser)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT r.id, r.title, r.description, r.status, r.version, r.assignee_id,
                            CASE WHEN r.status = 'IN_PROGRESS' THEN (
                                SELECT substring(h.text FROM length('Resolution returned: ') + 1)
                                FROM report_history h
                                WHERE h.report_id = r.id AND h.type = 'resolution_returned'
                                  AND h.text LIKE 'Resolution returned: %'
                                ORDER BY h.report_version DESC, h.created_at DESC, h.id DESC
                                LIMIT 1
                            ) ELSE NULL END AS return_reason
                     FROM reports r WHERE r.id = ? AND r.assignee_id = ?
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, user.id());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    AuthHttp.error(response, 404, "NOT_FOUND", "Report not found");
                    return null;
                }
                Map<String, Object> detail = new java.util.LinkedHashMap<>();
                detail.put("id", rows.getObject("id").toString());
                detail.put("title", rows.getString("title"));
                detail.put("description", rows.getString("description"));
                detail.put("status", rows.getString("status"));
                detail.put("version", rows.getLong("version"));
                detail.put("assigneeId", rows.getObject("assignee_id").toString());
                detail.put("returnReason", rows.getString("return_reason"));
                return detail;
            }
        } catch (SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Reports are temporarily unavailable");
            return null;
        }
    }
    @PostMapping("/api/developer/reports/{reportId}/start")
    Object start(@PathVariable("reportId") String reportId, @RequestBody String rawBody,
                 HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        final UUID id;
        try { id = UUID.fromString(reportId); }
        catch (IllegalArgumentException invalid) {
            AuthHttp.error(response, 404, "NOT_FOUND", "Report not found");
            return null;
        }
        final JsonNode body;
        try { body = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(rawBody); }
        catch (IOException invalid) {
            AuthHttp.error(response, 400, "INVALID_REQUEST", "Invalid expected version");
            return null;
        }
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("expectedVersion")
                || !body.get("expectedVersion").isIntegralNumber()
                || !body.get("expectedVersion").canConvertToLong()
                || body.get("expectedVersion").longValue() < 0
                || body.get("expectedVersion").longValue() >= 9007199254740991L) {
            AuthHttp.error(response, 400, "INVALID_REQUEST", "Invalid expected version");
            return null;
        }
        AuthProvider.CurrentUser user = (AuthProvider.CurrentUser)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        ActorTransactionDataSource.clearRevocation();
        try (var connection = actorDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long expectedVersion = body.get("expectedVersion").longValue();
                if (!lockAssignedReport(connection, id, user.id(), "ASSIGNED", expectedVersion)) {
                    connection.rollback();
                    AuthHttp.error(response, 409, "CONFLICT", "Report changed or is unavailable");
                    return null;
                }
                long nextVersion = expectedVersion + 1;
                try (var update = connection.prepareStatement(
                        "UPDATE reports SET status = 'IN_PROGRESS', version = ? WHERE id = ?")) {
                    update.setLong(1, nextVersion);
                    update.setObject(2, id);
                    update.executeUpdate();
                }
                appendLifecycleHistory(connection, id, user.id(), "report_started",
                        "Developer started working on the report", nextVersion);
                connection.commit();
                return Map.of("status", "IN_PROGRESS", "version", nextVersion);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (ActorTransactionDataSource.Revoked revoked) {
            unauthenticated(request, response);
            return null;
        } catch (SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Reports are temporarily unavailable");
            return null;
        } finally { ActorTransactionDataSource.clearRevocation(); }
    }

    @PostMapping("/api/developer/reports/{reportId}/resolution")
    Object resolution(@PathVariable("reportId") String reportId, @RequestBody String rawBody,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        final UUID id;
        try { id = UUID.fromString(reportId); }
        catch (IllegalArgumentException invalid) {
            AuthHttp.error(response, 404, "NOT_FOUND", "Report not found");
            return null;
        }
        final JsonNode body;
        try { body = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(rawBody); }
        catch (IOException invalid) {
            AuthHttp.error(response, 400, "INVALID_REQUEST", "Invalid resolution");
            return null;
        }
        if (body == null || !body.isObject() || body.size() != 2 || !body.has("expectedVersion")
                || !body.get("expectedVersion").isIntegralNumber() || !body.get("expectedVersion").canConvertToLong()
                || body.get("expectedVersion").longValue() < 0 || body.get("expectedVersion").longValue() >= 9007199254740991L
                || !body.has("resolution") || !body.get("resolution").isTextual()
                || body.get("resolution").textValue().isBlank() || body.get("resolution").textValue().length() > 10000
                || body.get("resolution").textValue().indexOf('\0') >= 0) {
            AuthHttp.error(response, 400, "INVALID_REQUEST", "Invalid resolution");
            return null;
        }
        var user = (AuthProvider.CurrentUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        ActorTransactionDataSource.clearRevocation();
        try (var connection = actorDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long expectedVersion = body.get("expectedVersion").longValue();
                if (!lockAssignedReport(connection, id, user.id(), "IN_PROGRESS", expectedVersion)) {
                    connection.rollback();
                    AuthHttp.error(response, 409, "CONFLICT", "Report changed or is unavailable");
                    return null;
                }
                UUID proposalId = UUID.randomUUID();
                try (var insert = connection.prepareStatement(
                        "INSERT INTO resolution_proposals (id, report_id, status, version, contents) VALUES (?, ?, 'PENDING', 0, ?)")) {
                    insert.setObject(1, proposalId);
                    insert.setObject(2, id);
                    insert.setString(3, body.get("resolution").textValue());
                    insert.executeUpdate();
                }
                try (var update = connection.prepareStatement(
                        "UPDATE reports SET current_proposal_id = ?, status = 'RESOLUTION_PROPOSED', version = version + 1 WHERE id = ?")) {
                    update.setObject(1, proposalId);
                    update.setObject(2, id);
                    update.executeUpdate();
                }
                long nextVersion = expectedVersion + 1;
                appendLifecycleHistory(connection, id, user.id(), "resolution_proposed",
                        "Developer proposed a resolution", nextVersion);
                connection.commit();
                return Map.of("status", "RESOLUTION_PROPOSED", "version", nextVersion);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (ActorTransactionDataSource.Revoked revoked) {
            unauthenticated(request, response);
            return null;
        } catch (SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Reports are temporarily unavailable");
            return null;
        } finally { ActorTransactionDataSource.clearRevocation(); }
    }

    private static boolean lockAssignedReport(java.sql.Connection connection, UUID reportId, UUID actorId,
                                              String expectedStatus, long expectedVersion) throws SQLException {
        try (var lock = connection.prepareStatement(
                "SELECT status, version FROM reports WHERE id = ? AND assignee_id = ? FOR UPDATE")) {
            lock.setObject(1, reportId);
            lock.setObject(2, actorId);
            try (var rows = lock.executeQuery()) {
                return rows.next() && expectedStatus.equals(rows.getString("status"))
                        && expectedVersion == rows.getLong("version");
            }
        }
    }

    private static void appendLifecycleHistory(java.sql.Connection connection, UUID reportId, UUID actorId,
                                               String type, String text, long version) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO report_history"
                + " (id, report_id, type, text, actor_id, report_version) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, reportId);
            insert.setString(3, type);
            insert.setString(4, text);
            insert.setObject(5, actorId);
            insert.setLong(6, version);
            if (insert.executeUpdate() != 1) throw new SQLException("Lifecycle history was not recorded");
        }
    }

    private static void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        AuthHttp.error(response, 401, "UNAUTHENTICATED", "Authentication required");
    }

}
