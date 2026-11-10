package mp4.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Ensures a provider cannot reflect its bearer credential into generated suggestions. */
class SocSuggestionCredentialReflectionTest {
    private static final String CANARY = "synthetic-reflected-bearer-canary-7f91";
    private HttpServer provider;
    private String responseContent;

    @BeforeEach void startProvider() throws Exception {
        responseContent = "[\"Inspect Bearer " + CANARY + "\"]";
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String body = "{\"choices\":[{\"message\":{\"content\":" + quote(responseContent) + "}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        provider.start();
    }

    @AfterEach void stopProvider() {
        if (provider != null) provider.stop(0);
    }

    @Test void rejectsCredentialReflectedInsideOtherwiseValidSuggestionWithoutLoggingIt() throws Exception {
        assertReflectedCredentialRejected();
    }

    @Test void rejectsCredentialReflectedWithUnicodeEscapeInsideOtherwiseValidSuggestion() throws Exception {
        String escapedCanary = CANARY.replace("a", "\\" + "u0061");
        responseContent = "[\"Inspect Bearer " + escapedCanary + "\"]";

        assertReflectedCredentialRejected();
    }

    @Test void rejectsEscapedCredentialInSecondConsecutiveSuggestionArray() throws Exception {
        String escapedCanary = CANARY.replace("a", "\\" + "u0061");
        responseContent = "[\"Inspect the login request\"]\n[\"Inspect Bearer " + escapedCanary + "\"]";

        assertReflectedCredentialRejected();
    }

    @Test void rejectsUnicodeEscapedCredentialAfterMissingComma() throws Exception {
        String escapedCanary = CANARY.replace("a", "\\" + "u0061");
        responseContent = "[\"Inspect the login request\" \"Inspect Bearer " + escapedCanary + "\"]";

        assertReflectedCredentialRejected();
    }

    private void assertReflectedCredentialRejected() throws Exception {
        var adapter = new SocSuggestionProvider(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build(), URI.create("http://127.0.0.1:" + provider.getAddress().getPort()), CANARY,
                "synthetic-model", Duration.ofSeconds(2));
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> {
                        SuggestionProvider.Failure failure = assertThrows(SuggestionProvider.Failure.class,
                                () -> adapter.suggest("synthetic title", "synthetic description"));
                        assertEquals(SuggestionProvider.FailureKind.INVALID, failure.kind());
                        assertFalse(failure.toString().contains(CANARY));
                    },
                    () -> assertFalse(appender.list.toString().contains(CANARY)));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
