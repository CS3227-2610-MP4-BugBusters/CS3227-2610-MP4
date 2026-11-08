package mp4.platform;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Keeps MVC method errors inside the same safe JSON response contract. */
@RestControllerAdvice
final class AuthHttpExceptionAdvice {
    @ExceptionHandler(NoResourceFoundException.class)
    void missingResource(HttpServletResponse response) throws IOException {
        AuthHttp.error(response, 404, "NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    void unsupportedMethod(HttpRequestMethodNotSupportedException failure, HttpServletResponse response) throws IOException {
        if (failure.getSupportedHttpMethods() != null) {
            response.setHeader("Allow", String.join(", ", failure.getSupportedHttpMethods().stream()
                    .map(method -> method.name()).sorted().toList()));
        }
        AuthHttp.error(response, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
    }
}
