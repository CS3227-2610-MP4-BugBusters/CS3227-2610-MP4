package mp4.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import mp4.maintainer.http.MaintainerHttpDtos;
import mp4.maintainer.storage.JdbcMaintainerRepository;
import mp4.maintainer.workflow.MaintainerContracts.*;
import mp4.maintainer.workflow.MaintainerService;
import mp4.maintainer.workflow.MaintainerService.ServiceException;
import mp4.maintainer.summary.QueueSummaryService;
import mp4.maintainer.summary.SummaryContracts;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

/** Trusted session and wire validation; lifecycle rules remain in the service. */
@RestController
final class MaintainerController {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MaintainerService service;
    private final QueueSummaryService summary;

    MaintainerController(DataSource database, QueueSummaryService summary) {
        service = new MaintainerService(new JdbcMaintainerRepository(database));
        this.summary = summary;
    }

    @GetMapping("/api/maintainer/reports")
    Object reports(HttpServletRequest request, HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            requireEmptyBody(request);
            Map<String, String[]> query = request.getParameterMap();
            if (!Set.of("status", "priority", "assigneeId", "limit", "offset").containsAll(query.keySet())
                    || query.values().stream().anyMatch(values -> values.length != 1)) throw invalid();
            Status status = query.containsKey("status") ? status(query.get("status")[0]) : null;
            Priority priority = query.containsKey("priority") ? Priority.valueOf(query.get("priority")[0]) : null;
            UUID assignee = query.containsKey("assigneeId") ? uuid(query.get("assigneeId")[0]) : null;
            int limit = query.containsKey("limit") ? number(query.get("limit")[0]) : 50;
            int offset = query.containsKey("offset") ? number(query.get("offset")[0]) : 0;
            return MaintainerHttpDtos.page(service.listReports(actor(), new QueryFilters(status, priority, assignee, limit, offset)));
        });
    }

    @GetMapping("/api/maintainer/reports/{reportId}")
    Object detail(@PathVariable("reportId") String reportId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            requireEmptyRequest(request);
            return MaintainerHttpDtos.detail(service.reportDetail(actor(), uuid(reportId)));
        });
    }

    @GetMapping("/api/maintainer/developers")
    Object developers(HttpServletRequest request, HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            requireEmptyRequest(request);
            return service.developers(actor()).stream().map(MaintainerHttpDtos::person).toList();
        });
    }

    @PutMapping("/api/maintainer/reports/{reportId}/priority")
    Object priority(@PathVariable("reportId") String reportId, HttpServletRequest request,
                    HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            JsonNode body = body(request, Set.of("expectedVersion", "priority"));
            return MaintainerHttpDtos.summary(service.updatePriority(actor(), uuid(reportId),
                    new PriorityChange(integer(body, "expectedVersion"), Priority.valueOf(text(body, "priority")))));
        });
    }

    @PostMapping("/api/maintainer/reports/{reportId}/assignment")
    Object assignment(@PathVariable("reportId") String reportId, HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            JsonNode body = body(request, Set.of("expectedVersion", "developerId", "reason"));
            return MaintainerHttpDtos.summary(service.assign(actor(), uuid(reportId), new AssignmentChange(
                    integer(body, "expectedVersion"), uuid(text(body, "developerId")), optionalText(body, "reason"))));
        });
    }

    @PostMapping("/api/maintainer/reports/{reportId}/resolution/return")
    Object returnResolution(@PathVariable("reportId") String reportId, HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            JsonNode body = body(request, Set.of("expectedVersion", "proposalId", "proposalVersion", "reason"));
            return MaintainerHttpDtos.summary(service.returnResolution(actor(), uuid(reportId), new ReturnResolution(
                    integer(body, "expectedVersion"), uuid(text(body, "proposalId")),
                    integer(body, "proposalVersion"), text(body, "reason"))));
        });
    }

    @PostMapping("/api/maintainer/reports/{reportId}/close")
    Object close(@PathVariable("reportId") String reportId, HttpServletRequest request,
                 HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            JsonNode body = body(request, Set.of("expectedVersion", "proposalId", "proposalVersion", "reviewed"));
            JsonNode reviewed = body.get("reviewed");
            if (reviewed == null || !reviewed.isBoolean()) throw invalid();
            return MaintainerHttpDtos.summary(service.close(actor(), uuid(reportId), new CloseReport(
                    integer(body, "expectedVersion"), uuid(text(body, "proposalId")),
                    integer(body, "proposalVersion"), reviewed.booleanValue())));
        });
    }

    @PostMapping("/api/maintainer/reports/{reportId}/reopen")
    Object reopen(@PathVariable("reportId") String reportId, HttpServletRequest request,
                  HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            JsonNode body = body(request, Set.of("expectedVersion", "reason"));
            return MaintainerHttpDtos.summary(service.reopen(actor(), uuid(reportId), new ReopenReport(
                    integer(body, "expectedVersion"), text(body, "reason"))));
        });
    }

    @PostMapping("/api/maintainer/reports/{reportId}/comments")
    Object comment(@PathVariable("reportId") String reportId, HttpServletRequest request,
                   HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            JsonNode body = body(request, Set.of("expectedVersion", "text"));
            CommentResult result = service.addComment(actor(), uuid(reportId), new AddComment(
                    integer(body, "expectedVersion"), text(body, "text")));
            response.setStatus(201);
            return MaintainerHttpDtos.commentResult(result);
        });
    }

    @PostMapping("/api/maintainer/queue-summary")
    Object queueSummary(HttpServletRequest request, HttpServletResponse response) throws IOException {
        return read(request, response, () -> {
            JsonNode body = body(request, Set.of("filters", "regenerate"));
            JsonNode filters = body.get("filters"), regenerate = body.get("regenerate");
            if (filters == null || !filters.isObject() || regenerate == null || !regenerate.isBoolean()) throw invalid();
            var fields = filters.fieldNames();
            while (fields.hasNext()) if (!Set.of("status", "priority", "assigneeId").contains(fields.next())) throw invalid();
            Status status = filters.has("status") ? status(text(filters, "status")) : null;
            Priority priority = filters.has("priority") ? Priority.valueOf(text(filters, "priority")) : null;
            UUID assignee = filters.has("assigneeId") ? uuid(text(filters, "assigneeId")) : null;
            var trusted = actor();
            var session = request.getSession(false);
            if (session == null) throw new SummaryContracts.SummaryFailure(SummaryContracts.FailureCode.UNAUTHENTICATED);
            var caller = new SummaryContracts.Actor(trusted.accountId(), trusted.role(), trusted.active(),
                    trusted.generation(), session.getId());
            return MaintainerHttpDtos.summaryResult(summary.generate(caller,
                    new SummaryContracts.Filters(status, priority, assignee), regenerate.booleanValue()));
        });
    }

    private Object read(HttpServletRequest request, HttpServletResponse response, Query query) throws IOException {
        AuthHttp.noStore(response);
        try { return query.get(); }
        catch (SummaryContracts.SummaryFailure failure) {
            if (failure.retryAfterSeconds() != null) response.setHeader("Retry-After", failure.retryAfterSeconds().toString());
            AuthHttp.error(response, failure.statusCode(), failure.code(), failure.getMessage());
        }
        catch (ServiceException failure) {
            AuthHttp.error(response, failure.statusCode(), failure.code(), failure.getMessage());
        } catch (IllegalArgumentException failure) {
            AuthHttp.error(response, 400, "VALIDATION_FAILED", "Invalid Maintainer request");
        }
        return null;
    }

    static Actor actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthProvider.CurrentUser user))
            throw new ServiceException("UNAUTHENTICATED", 401, "Authentication required");
        return new Actor(user.id(), Role.valueOf(user.role()), true, user.generation());
    }

    private static void requireEmptyRequest(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw invalid();
        requireEmptyBody(request);
    }
    private static void requireEmptyBody(HttpServletRequest request) throws IOException {
        if (request.getInputStream().readNBytes(1).length != 0) throw invalid();
    }
    private static int number(String value) {
        if (!value.matches("0|[1-9][0-9]{0,9}")) throw invalid();
        return Integer.parseInt(value);
    }
    private static JsonNode body(HttpServletRequest request, Set<String> allowed) throws IOException {
        if (request.getQueryString() != null || request.getContentType() == null) throw invalid();
        MediaType type = MediaType.parseMediaType(request.getContentType());
        if (!type.getType().equalsIgnoreCase("application") || !type.getSubtype().equalsIgnoreCase("json")
                || type.getCharset() != null && !type.getCharset().equals(StandardCharsets.UTF_8)) throw invalid();
        if (request.getContentLengthLong() > 65536) throw invalid();
        byte[] bytes = request.getInputStream().readNBytes(65537);
        if (bytes.length > 65536) throw invalid();
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode body = JSON.readTree(decoded);
            if (body == null || !body.isObject()) throw invalid();
            var names = body.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw invalid();
            return body;
        } catch (IOException malformed) { throw invalid(); }
    }
    private static long integer(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        long number = value.longValue();
        if (number < 0 || number > mp4.maintainer.workflow.MaintainerContracts.MAX_SAFE_VERSION) throw invalid();
        return number;
    }
    private static String text(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }
    private static String optionalText(JsonNode body, String key) { return body.has(key) ? text(body, key) : null; }
    private static Status status(String value) {
        for (Status status : Status.values()) if (status.wireLabel().equals(value)) return status;
        throw invalid();
    }
    private static UUID uuid(String value) {
        UUID result = UUID.fromString(value);
        if (!result.toString().equalsIgnoreCase(value)) throw invalid();
        return result;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid request"); }
    @FunctionalInterface private interface Query { Object get() throws IOException; }
}
