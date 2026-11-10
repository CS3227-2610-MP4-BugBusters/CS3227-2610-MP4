package mp4.reporter;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import mp4.reporter.ReporterReportStore.Page;
import mp4.reporter.ReporterReportStore.RateLimited;
import mp4.reporter.ReporterReportStore.Report;
import mp4.reporter.ReporterReportStore.Summary;

/** HTTP adapter for Reporter report create, list and read; authentication supplies the trusted actor. */
public final class ReporterReportsHttpHandler {
    private static final String COLLECTION_PATH = "/api/reporter/reports";
    private static final String ITEM_PREFIX = COLLECTION_PATH + "/";
    private static final Set<String> CREATE_FIELDS = Set.of("title", "description");
    // Largest legal body is about 121 KB: 20,200 code units written as 6-byte \\u escapes, plus syntax.
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int MAX_TITLE_UNITS = 200;
    private static final int MAX_DESCRIPTION_UNITS = 20_000;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Error codes with their fixed messages. Messages never carry request data. */
    private enum ErrorCode {
        VALIDATION_FAILED(400, "The request is invalid."),
        UNAUTHENTICATED(401, "Authentication is required."),
        FORBIDDEN(403, "You do not have access to this resource."),
        REPORT_NOT_FOUND(404, "The report was not found."),
        METHOD_NOT_ALLOWED(405, "The method is not allowed."),
        RATE_LIMITED(429, "Too many reports were created recently. Try again later."),
        INTERNAL_ERROR(500, "The request could not be completed.");

        final int status;
        final String message;

        ErrorCode(int status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    private final ReporterReportStore store;

    public ReporterReportsHttpHandler(DataSource database) {
        this.store = new ReporterReportStore(database);
    }

    public void handle(HttpExchange exchange, UUID actorId, String role, boolean active) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        boolean collection = COLLECTION_PATH.equals(path);
        boolean item = path != null && path.startsWith(ITEM_PREFIX)
                && path.indexOf('/', ITEM_PREFIX.length()) < 0;
        if (!collection && !item) {
            sendError(exchange, ErrorCode.REPORT_NOT_FOUND);
            return;
        }
        boolean post = collection && method.equals("POST");
        if (!post && !method.equals("GET")) {
            exchange.getResponseHeaders().set("Allow", collection ? "GET, POST" : "GET");
            sendError(exchange, ErrorCode.METHOD_NOT_ALLOWED);
            return;
        }
        if (actorId == null) {
            sendError(exchange, ErrorCode.UNAUTHENTICATED);
            return;
        }
        if (!active || !"REPORTER".equals(role)) {
            sendError(exchange, ErrorCode.FORBIDDEN);
            return;
        }
        try {
            if (post) {
                create(exchange, actorId);
            } else if (collection) {
                list(exchange, actorId);
            } else {
                read(exchange, actorId, path.substring(ITEM_PREFIX.length()));
            }
        } catch (SQLException | RuntimeException failure) {
            sendError(exchange, ErrorCode.INTERNAL_ERROR);
        }
    }

    private void create(HttpExchange exchange, UUID actorId) throws IOException, SQLException {
        NewReport request;
        try {
            request = parseCreate(readBounded(exchange));
        } catch (BodyTooLarge | IOException | IllegalArgumentException invalidBody) {
            sendError(exchange, ErrorCode.VALIDATION_FAILED);
            return;
        }
        Report report;
        try {
            report = store.create(actorId, request.title(), request.description());
        } catch (RateLimited limited) {
            exchange.getResponseHeaders().set("Retry-After", Long.toString(limited.retryAfterSeconds()));
            sendError(exchange, ErrorCode.RATE_LIMITED);
            return;
        }
        exchange.getResponseHeaders().set("Location", ITEM_PREFIX + report.id());
        sendJson(exchange, 201, detailJson(report));
    }

    private void list(HttpExchange exchange, UUID actorId) throws IOException, SQLException {
        int[] paging;
        try {
            paging = parsePaging(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException invalidQuery) {
            sendError(exchange, ErrorCode.VALIDATION_FAILED);
            return;
        }
        sendJson(exchange, 200, pageJson(store.list(actorId, paging[0], paging[1])));
    }

    private void read(HttpExchange exchange, UUID actorId, String rawId) throws IOException, SQLException {
        // Non-canonical, missing and other-Reporter IDs all take the same REPORT_NOT_FOUND path.
        Optional<Report> report = CANONICAL_UUID.matcher(rawId).matches()
                ? store.find(actorId, UUID.fromString(rawId))
                : Optional.empty();
        if (report.isEmpty()) {
            sendError(exchange, ErrorCode.REPORT_NOT_FOUND);
            return;
        }
        sendJson(exchange, 200, detailJson(report.get()));
    }

    private static NewReport parseCreate(byte[] body) {
        Map<String, String> fields = ReporterJson.parseStringObject(body);
        if (!fields.keySet().equals(CREATE_FIELDS)) {
            throw new IllegalArgumentException("Body must contain exactly title and description");
        }
        String title = fields.get("title");
        String description = fields.get("description");
        if (!isStorableText(title) || isBlank(title) || title.length() > MAX_TITLE_UNITS
                || title.indexOf('\r') >= 0 || title.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid title");
        }
        if (!isStorableText(description) || isBlank(description) || description.length() > MAX_DESCRIPTION_UNITS) {
            throw new IllegalArgumentException("Invalid description");
        }
        return new NewReport(title, description);
    }

    /** False for U+0000 or an unpaired surrogate, which PostgreSQL text cannot hold faithfully. */
    private static boolean isStorableText(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\0' || Character.isLowSurrogate(c)) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                i++;
            }
        }
        return true;
    }

    private static boolean isBlank(String value) {
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (!isUnicodeWhitespace(codePoint)) return false;
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /** The Unicode White_Space property: Zs, Zl and Zp plus U+0009..U+000D and U+0085. */
    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isSpaceChar(codePoint) || (codePoint >= 0x09 && codePoint <= 0x0D) || codePoint == 0x85;
    }

    /** Returns {page, pageSize}. Only page and pageSize, each at most once, as positive decimal integers. */
    private static int[] parsePaging(String query) {
        Map<String, String> parameters = new HashMap<>();
        if (query != null && !query.isEmpty()) {
            for (String part : query.split("&", -1)) {
                int separator = part.indexOf('=');
                String key = separator < 0 ? part : part.substring(0, separator);
                String value = separator < 0 ? "" : part.substring(separator + 1);
                if ((!key.equals("page") && !key.equals("pageSize")) || parameters.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("Unsupported or repeated query parameter");
                }
            }
        }
        int page = parsePositiveInteger(parameters.get("page"), 1);
        int pageSize = parsePositiveInteger(parameters.get("pageSize"), DEFAULT_PAGE_SIZE);
        if (pageSize > MAX_PAGE_SIZE) throw new IllegalArgumentException("Page size exceeds maximum");
        return new int[] {page, pageSize};
    }

    private static int parsePositiveInteger(String value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value.isEmpty()) throw new IllegalArgumentException("Empty pagination value");
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                throw new IllegalArgumentException("Pagination value must be decimal");
            }
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new IllegalArgumentException("Pagination value must be positive");
            return parsed;
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("Pagination value is too large", overflow);
        }
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

    private static String detailJson(Report report) {
        return "{\"id\":" + ReporterJson.quote(report.id().toString())
                + ",\"title\":" + ReporterJson.quote(report.title())
                + ",\"description\":" + ReporterJson.quote(report.description())
                + ",\"status\":" + ReporterJson.quote(report.status())
                + ",\"version\":" + report.version()
                + ",\"createdAt\":" + timestamp(report.createdAt())
                + ",\"updatedAt\":" + timestamp(report.updatedAt()) + "}";
    }

    private static String pageJson(Page page) {
        var result = new StringBuilder("{\"items\":[");
        boolean first = true;
        for (Summary item : page.items()) {
            if (!first) result.append(',');
            first = false;
            result.append("{\"id\":").append(ReporterJson.quote(item.id().toString()))
                    .append(",\"title\":").append(ReporterJson.quote(item.title()))
                    .append(",\"status\":").append(ReporterJson.quote(item.status()))
                    .append(",\"createdAt\":").append(timestamp(item.createdAt()))
                    .append(",\"updatedAt\":").append(timestamp(item.updatedAt())).append('}');
        }
        return result.append("],\"page\":").append(page.page())
                .append(",\"pageSize\":").append(page.pageSize())
                .append(",\"totalItems\":").append(page.totalItems()).append('}').toString();
    }

    /** ISO-8601 UTC with milliseconds; sub-millisecond digits are truncated, as PostgreSQL's MS does. */
    private static String timestamp(Instant instant) {
        return ReporterJson.quote(TIMESTAMP.format(instant.truncatedTo(ChronoUnit.MILLIS)));
    }

    private static void sendError(HttpExchange exchange, ErrorCode error) throws IOException {
        sendJson(exchange, error.status, "{\"code\":" + ReporterJson.quote(error.name())
                + ",\"message\":" + ReporterJson.quote(error.message)
                + ",\"requestId\":" + ReporterJson.quote(UUID.randomUUID().toString()) + "}");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class BodyTooLarge extends Exception {}

    private record NewReport(String title, String description) {}
}
