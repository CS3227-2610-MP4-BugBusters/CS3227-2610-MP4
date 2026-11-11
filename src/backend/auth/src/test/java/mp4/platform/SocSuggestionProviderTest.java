package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Local HTTP boundary tests for the temporary SoC provider adapter. */
class SocSuggestionProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEST_TOKEN = "synthetic-test-token";
    private HttpServer provider;
    private HttpServer redirectTarget;
    private String providerBody;
    private String authorization;
    private int providerCalls;

    @BeforeEach void startProvider() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/chat/completions", exchange -> {
            providerCalls++;
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            providerBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, success("[\"Compare the first trace\"]", 81, 12));
        });
        provider.start();

        redirectTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirectTarget.createContext("/chat/completions", exchange -> {
            providerCalls++;
            exchange.close();
        });
        redirectTarget.start();
    }

    @AfterEach void stopProvider() {
        if (provider != null) provider.stop(0);
        if (redirectTarget != null) redirectTarget.stop(0);
    }

    @Test void sendsOnlyBoundedReportContextAsUntrustedAndParsesUsage() throws Exception {
        var adapter = adapter(base(provider), Duration.ofSeconds(2));
        String title = "t".repeat(512);
        String description = "d".repeat(8192);

        var response = adapter.suggest(title, description);

        assertEquals("[\"Compare the first trace\"]", response.rawOutput());
        assertNotNull(response.usage());
        assertEquals(81L, response.usage().inputTokens());
        assertEquals(12L, response.usage().outputTokens());
        assertEquals("team-test-model", adapter.modelName());
        assertEquals("Bearer " + TEST_TOKEN, authorization);

        JsonNode request = JSON.readTree(providerBody);
        assertEquals(java.util.Set.of("model", "messages", "temperature", "max_tokens", "reasoning_effort"),
                iterableFields(request));
        assertEquals("team-test-model", request.path("model").asText());
        assertEquals(0, request.path("temperature").asInt());
        assertEquals(384, request.path("max_tokens").asInt());
        assertEquals("none", request.path("reasoning_effort").asText());
        assertEquals(2, request.path("messages").size());
        assertEquals("system", request.path("messages").get(0).path("role").asText());
        assertEquals("user", request.path("messages").get(1).path("role").asText());
        String prompt = request.path("messages").get(1).path("content").asText();
        assertTrue(prompt.toLowerCase().contains("untrusted"), "report content must be marked untrusted");
        assertTrue(prompt.contains(title));
        assertTrue(prompt.contains(description));
        assertFalse(prompt.contains(TEST_TOKEN));
        assertEquals(1, providerCalls);
    }

    @Test void omitsUsageWhenProviderDoesNotReportIt() throws Exception {
        provider.removeContext("/chat/completions");
        provider.createContext("/chat/completions", exchange -> {
            providerCalls++;
            providerBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"[]\"}}]}");
        });

        var response = adapter(base(provider), Duration.ofSeconds(2)).suggest("title", "description");

        assertEquals("[]", response.rawOutput());
        assertNull(response.usage());
    }

    @Test void rejectsTitleAndDescriptionOverUtf8ByteCapsBeforeCallingProvider() {
        var adapter = adapter(base(provider), Duration.ofSeconds(2));

        RuntimeException titleFailure = assertThrows(RuntimeException.class,
                () -> adapter.suggest("é".repeat(257), "description"));
        assertEquals(0, providerCalls, "oversized UTF-8 title must be rejected before provider invocation");
        assertFalse(titleFailure.toString().contains(TEST_TOKEN));

        RuntimeException descriptionFailure = assertThrows(RuntimeException.class,
                () -> adapter.suggest("title", "é".repeat(4097)));
        assertEquals(0, providerCalls, "oversized UTF-8 description must be rejected before provider invocation");
        assertFalse(descriptionFailure.toString().contains(TEST_TOKEN));
    }

    @Test void doesNotFollowRedirectToAnotherOrigin() throws Exception {
        provider.removeContext("/chat/completions");
        provider.createContext("/chat/completions", exchange -> {
            providerCalls++;
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", base(redirectTarget) + "/chat/completions");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> adapter(base(provider), Duration.ofSeconds(2)).suggest("title", "description"));

        assertEquals(1, providerCalls);
        assertFalse(failure.toString().contains(TEST_TOKEN));
    }

    @Test void rejectsResponseLargerThan64KiBWhileStreaming() throws Exception {
        provider.removeContext("/chat/completions");
        provider.createContext("/chat/completions", exchange -> {
            providerCalls++;
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[8192];
            try (var body = exchange.getResponseBody()) {
                for (int i = 0; i < 9; i++) body.write(chunk);
            } catch (IOException ignored) {
                // The adapter may close the stream as soon as the configured cap is exceeded.
            }
        });

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> adapter(base(provider), Duration.ofSeconds(3)).suggest("title", "description"));

        assertFalse(failure.toString().contains(TEST_TOKEN));
        assertFalse(failure.toString().contains("choices"));
    }

    @Test void deadlineIncludesResponseBodyStreaming() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        AtomicReference<Thread> providerThread = new AtomicReference<>();
        provider.removeContext("/chat/completions");
        provider.createContext("/chat/completions", exchange -> {
            providerCalls++;
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            providerThread.set(Thread.currentThread());
            headersSent.countDown();
            try (var body = exchange.getResponseBody()) {
                body.write("{\"choices\":[".getBytes(StandardCharsets.UTF_8));
                body.flush();
                Thread.sleep(1500);
                body.write("{}]}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException | InterruptedException ignored) {
                // The deadline may cancel the request while the response is being streamed.
            }
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var call = executor.submit(() -> adapter(base(provider), Duration.ofMillis(300))
                    .suggest("title", "description"));
            assertTrue(headersSent.await(2, TimeUnit.SECONDS), "fake provider must send headers before deadline evaluation");
            long deadlineObserved = System.nanoTime();
            ExecutionException wrapped = assertThrows(ExecutionException.class,
                    () -> call.get(1200, TimeUnit.MILLISECONDS));
            RuntimeException failure = assertInstanceOf(RuntimeException.class, wrapped.getCause());
            assertTrue(Duration.ofNanos(System.nanoTime() - deadlineObserved).toMillis() < 1100,
                    "provider call must fail before the delayed body is released");
            assertTrue(providerThread.get().isAlive(), "fake provider must still be holding the delayed response body");
            assertFalse(failure.toString().contains(TEST_TOKEN));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void providerFailureDoesNotExposeSecretOrProviderBodyInLogsOrException() throws Exception {
        String privateBody = "synthetic provider failure with " + TEST_TOKEN;
        provider.removeContext("/chat/completions");
        provider.createContext("/chat/completions", exchange -> {
            providerCalls++;
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 500, privateBody);
        });
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> adapter(base(provider), Duration.ofSeconds(2)).suggest("title", "description"));

            String logged = appender.list.toString();
            assertFalse(failure.toString().contains(TEST_TOKEN));
            assertFalse(failure.toString().contains(privateBody));
            assertFalse(logged.contains(TEST_TOKEN));
            assertFalse(logged.contains(privateBody));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    private static SocSuggestionProvider adapter(URI baseUri, Duration timeout) {
        return new SocSuggestionProvider(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build(), baseUri, TEST_TOKEN, "team-test-model", timeout);
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static String success(String content, int inputTokens, int outputTokens) {
        return "{\"choices\":[{\"message\":{\"content\":" + quote(content)
                + "}}],\"usage\":{\"prompt_tokens\":" + inputTokens
                + ",\"completion_tokens\":" + outputTokens + "}}";
    }

    private static String quote(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static java.util.Set<String> iterableFields(JsonNode node) {
        var fields = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
