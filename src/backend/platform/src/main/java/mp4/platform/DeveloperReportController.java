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
             var statement = connection.prepareStatement(
                     "SELECT id, title, description, status, version, assignee_id FROM reports WHERE id = ? AND assignee_id = ?")) {
            statement.setObject(1, id);
            statement.setObject(2, user.id());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    AuthHttp.error(response, 404, "NOT_FOUND", "Report not found");
                    return null;
                }
                return Map.of("id", rows.getObject("id").toString(), "title", rows.getString("title"),
                        "description", rows.getString("description"), "status", rows.getString("status"),
                        "version", rows.getLong("version"), "assigneeId", rows.getObject("assignee_id").toString());
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
