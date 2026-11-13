package mp4.platform;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import mp4.developer.SharedInvestigationNotesStore;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class DeveloperSharedInvestigationNotesController {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final SharedInvestigationNotesStore notes;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    DeveloperSharedInvestigationNotesController(DataSource dataSource) {
        notes = new SharedInvestigationNotesStore(dataSource);
    }

    @GetMapping("/api/developer/reports/{reportId}/shared-notes/draft")
    Object read(@PathVariable("reportId") String reportId, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        UUID id = parseId(reportId, response);
        if (id == null) return null;
        try {
            var result = notes.read(currentActor(), id);
            return switch (result.outcome()) {
                case OK -> Map.of("text", result.draft().text(), "draftRevision", result.draft().draftRevision(),
                        "reportVersion", result.draft().reportVersion());
                case FORBIDDEN -> { AuthHttp.error(response, 403, "FORBIDDEN", "Access denied"); yield null; }
                case VERSION_LIMIT -> { AuthHttp.error(response, 409, "VERSION_LIMIT", "Note version cannot be safely represented"); yield null; }
                default -> throw new IllegalStateException("Unexpected shared-note read outcome");
            };
        } catch (SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Notes are temporarily unavailable");
            return null;
        }
    }

    @PutMapping("/api/developer/reports/{reportId}/shared-notes/draft")
    Object save(@PathVariable("reportId") String reportId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        UUID id = parseId(reportId, response);
        if (id == null) return null;
        JsonNode body;
        String rawBody = boundedBody(request);
        if (rawBody == null) { validationError(response); return null; }
        try { body = mapper.readTree(rawBody); }
        catch (IOException invalid) { validationError(response); return null; }
        if (!validTextRequest(body)) { validationError(response); return null; }
        try {
            var result = notes.save(currentActor(), id, body.get("expectedDraftRevision").longValue(), body.get("text").textValue());
            return switch (result.outcome()) {
                case SAVED -> Map.of("draftRevision", result.draft().draftRevision());
                case FORBIDDEN -> { AuthHttp.error(response, 403, "FORBIDDEN", "Access denied"); yield null; }
                case INVALID_STATE -> { AuthHttp.error(response, 409, "INVALID_STATE", "Notes cannot be changed in this report state"); yield null; }
                case STALE_VERSION -> { AuthHttp.error(response, 409, "STALE_VERSION", "Draft revision is out of date"); yield null; }
                case VERSION_LIMIT -> { AuthHttp.error(response, 409, "VERSION_LIMIT", "Draft revision limit reached"); yield null; }
                default -> throw new IllegalStateException("Unexpected shared-note Save outcome");
            };
        } catch (SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Notes are temporarily unavailable");
            return null;
        }
    }

    @PostMapping("/api/developer/reports/{reportId}/shared-notes/share")
    Object share(@PathVariable("reportId") String reportId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        UUID id = parseId(reportId, response);
        if (id == null) return null;
        JsonNode body;
        String rawBody = boundedBody(request);
        if (rawBody == null) { validationError(response); return null; }
        try { body = mapper.readTree(rawBody); }
        catch (IOException invalid) { validationError(response); return null; }
        if (!validShareRequest(body)) { validationError(response); return null; }
        try {
            var result = notes.share(currentActor(), id, body.get("expectedDraftRevision").longValue(),
                    body.get("expectedReportVersion").longValue());
            return switch (result.outcome()) {
                case SHARED -> Map.of("reportVersion", result.reportVersion(), "changed", result.changed());
                case FORBIDDEN -> { AuthHttp.error(response, 403, "FORBIDDEN", "Access denied"); yield null; }
                case INVALID_STATE -> { AuthHttp.error(response, 409, "INVALID_STATE", "Notes cannot be shared in this report state"); yield null; }
                case STALE_VERSION -> { AuthHttp.error(response, 409, "STALE_VERSION", "Draft or report version is out of date"); yield null; }
                case EMPTY_DRAFT -> { AuthHttp.error(response, 409, "EMPTY_DRAFT", "A nonblank saved note is required"); yield null; }
                case VERSION_LIMIT -> { AuthHttp.error(response, 409, "VERSION_LIMIT", "Report version limit reached"); yield null; }
                default -> throw new IllegalStateException("Unexpected shared-note Share outcome");
            };
        } catch (SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Notes are temporarily unavailable");
            return null;
        }
    }

    private static UUID parseId(String value, HttpServletResponse response) throws IOException {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { AuthHttp.error(response, 404, "NOT_FOUND", "Report not found"); return null; }
    }

    private static boolean validTextRequest(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2 || !body.has("text") || !body.has("expectedDraftRevision")) return false;
        JsonNode text = body.get("text"), revision = body.get("expectedDraftRevision");
        if (!text.isTextual() || !safeVersion(revision)) return false;
        String value = text.textValue();
        return validText(value);
    }

    private static boolean validShareRequest(JsonNode body) {
        return body != null && body.isObject() && body.size() == 2 && body.has("expectedDraftRevision")
                && body.has("expectedReportVersion") && safeVersion(body.get("expectedDraftRevision"))
                && safeVersion(body.get("expectedReportVersion"));
    }

    private static boolean safeVersion(JsonNode version) {
        return version != null && version.isIntegralNumber() && version.canConvertToLong()
                && version.longValue() >= 0 && version.longValue() <= SharedInvestigationNotesStore.MAX_SAFE_VERSION;
    }

    private static boolean validText(String value) {
        if (value.length() > 10_000 || value.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(ch)) return false;
        }
        return true;
    }

    private static String boundedBody(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidEncoding) { return null; }
    }

    private static void validationError(HttpServletResponse response) throws IOException {
        AuthHttp.error(response, 400, "VALIDATION_FAILED", "Invalid shared notes request");
    }

    private static SharedInvestigationNotesStore.Actor currentActor() {
        AuthProvider.CurrentUser user = (AuthProvider.CurrentUser)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return new SharedInvestigationNotesStore.Actor(user.id(), user.role(), user.generation());
    }
}
