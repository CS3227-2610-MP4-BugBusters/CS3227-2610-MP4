package mp4.platform;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import mp4.developer.PrivateNotesStore;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class DeveloperPrivateNotesController {
    private final PrivateNotesStore notes;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    DeveloperPrivateNotesController(DataSource dataSource) { notes = new PrivateNotesStore(dataSource); }

    @GetMapping("/api/developer/reports/{reportId}/notes")
    Object read(@PathVariable("reportId") String reportId, HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        UUID id = parseId(reportId, response);
        if (id == null) return null;
        try {
            var result = notes.read(currentActor(), id);
            return switch (result.outcome()) {
                case AUTHORIZED -> Map.of("text", result.notes().text(), "revision", result.notes().revision());
                case FORBIDDEN -> { AuthHttp.error(response, 403, "FORBIDDEN", "Access denied"); yield null; }
                case UNAVAILABLE -> { AuthHttp.error(response, 404, "NOT_FOUND", "Report not found"); yield null; }
                default -> throw new IllegalStateException("Unexpected notes read outcome");
            };
        } catch (SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Notes are temporarily unavailable");
            return null;
        }
    }

    @PutMapping("/api/developer/reports/{reportId}/notes")
    Object save(@PathVariable("reportId") String reportId, @RequestBody String rawBody,
                HttpServletResponse response) throws IOException {
        AuthHttp.noStore(response);
        UUID id = parseId(reportId, response);
        if (id == null) return null;
        JsonNode body;
        try { body = mapper.readTree(rawBody); }
        catch (IOException invalid) {
            AuthHttp.error(response, 400, "VALIDATION_FAILED", "Invalid notes request");
            return null;
        }
        if (!validBody(body)) {
            AuthHttp.error(response, 400, "VALIDATION_FAILED", "Invalid notes request");
            return null;
        }
        try {
            long expected = body.get("expectedRevision").longValue();
            var result = notes.save(currentActor(), id, expected, body.get("text").textValue());
            return switch (result.outcome()) {
                case SAVED -> Map.of("text", body.get("text").textValue(), "revision", result.revision());
                case AUTHORIZED -> throw new IllegalStateException("Unexpected notes save outcome");
                case FORBIDDEN -> { AuthHttp.error(response, 403, "FORBIDDEN", "Access denied"); yield null; }
                case UNAVAILABLE -> { AuthHttp.error(response, 404, "NOT_FOUND", "Report not found"); yield null; }
                case INVALID_STATE -> { AuthHttp.error(response, 409, "INVALID_STATE", "Notes cannot be changed in this report state"); yield null; }
                case STALE_VERSION -> { AuthHttp.error(response, 409, "STALE_VERSION", "Notes changed; reload before saving"); yield null; }
                case VERSION_LIMIT -> { AuthHttp.error(response, 409, "VERSION_LIMIT", "Notes revision limit reached"); yield null; }
            };
        } catch (SQLException unavailable) {
            AuthHttp.error(response, 503, "STORAGE_UNAVAILABLE", "Notes are temporarily unavailable");
            return null;
        }
    }

    private static UUID parseId(String value, HttpServletResponse response) throws IOException {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) {
            AuthHttp.error(response, 404, "NOT_FOUND", "Report not found");
            return null;
        }
    }

    private static boolean validBody(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2 || !body.has("text") || !body.has("expectedRevision")) return false;
        JsonNode text = body.get("text"), revision = body.get("expectedRevision");
        if (!text.isTextual() || !revision.isIntegralNumber() || !revision.canConvertToLong()) return false;
        String value = text.textValue();
        long expected = revision.longValue();
        if (expected < 0 || expected > PrivateNotesStore.MAX_SAFE_REVISION || value.length() > 10000 || value.indexOf('\0') >= 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(ch)) return false;
        }
        return true;
    }

    private static PrivateNotesStore.Actor currentActor() {
        AuthProvider.CurrentUser user = (AuthProvider.CurrentUser)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return new PrivateNotesStore.Actor(user.id(), user.role(), user.generation());
    }
}
