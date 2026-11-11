package mp4.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class SuggestionController {
    private final SuggestionService suggestions;

    SuggestionController(SuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    @PostMapping("/api/reports/{reportId}/checklist/suggestions")
    void suggestions(@PathVariable("reportId") UUID reportId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (request.getQueryString() != null || !isJson(request.getContentType())
                || !exactEmptyObject(request)) {
            AuthHttp.error(response, 400, "VALIDATION_FAILED", "Invalid suggestion request");
            return;
        }
        suggestions.suggest(reportId, request, response);
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return "application".equalsIgnoreCase(parsed.getType())
                    && "json".equalsIgnoreCase(parsed.getSubtype());
        } catch (InvalidMediaTypeException invalid) {
            return false;
        }
    }

    private static boolean exactEmptyObject(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(3);
        return body.length == 2 && body[0] == '{' && body[1] == '}';
    }
}
