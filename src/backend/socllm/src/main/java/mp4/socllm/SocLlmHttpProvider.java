package mp4.socllm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import mp4.maintainer.summary.SummaryContracts.FailureCode;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;

/** Fixed endpoint transport with bounded bodies, no redirects or automatic retries. */
public final class SocLlmHttpProvider {
    public static final URI ENDPOINT = URI.create("https://soclaas-api.comp.nus.edu.sg/v1/chat/completions");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration MAX_REQUEST_TIMEOUT = SocLlmTaskPolicy.DEADLINE_CEILING;
    private static final int MAX_INPUT_BYTES = SocLlmTaskPolicy.INPUT_BYTES_CEILING;
    private static final int MAX_INSTRUCTION_BYTES = SocLlmTaskPolicy.INSTRUCTION_BYTES_CEILING;
    /** 384 KiB: worst-case JSON escaping of the input ceiling plus instructions and framing. */
    private static final int MAX_REQUEST_BYTES = 393_216;
    private static final int MAX_RESPONSE_BYTES = 65_536;

    private final HttpClient client;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Clock clock;

    public SocLlmHttpProvider(String apiKey, String model, Clock clock) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper(), ENDPOINT, apiKey, model, clock);
    }

    /** Package-scoped constructor permits loopback transport tests without changing production routing. */
    SocLlmHttpProvider(HttpClient client, ObjectMapper json, URI endpoint,
            String apiKey, String model, Clock clock) {
        this.client = Objects.requireNonNull(client);
        this.json = Objects.requireNonNull(json);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.clock = Objects.requireNonNull(clock);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !isLoopbackTestEndpoint(endpoint)) {
            throw new IllegalArgumentException("SoCLaaS endpoint must use HTTPS.");
        }
        this.apiKey = SocLlmContracts.canonicalApiKey(apiKey);
        this.model = required(model, "model");
    }

    public String generate(String instructions, String input, int outputTokens, Duration callerBound) {
        SocLlmTransactionBoundary.requireNoCallerTransaction();
        validate(instructions, input, outputTokens, callerBound);
        byte[] body = requestBody(instructions, input, outputTokens);
        if (body.length > MAX_REQUEST_BYTES) throw invalidOutput();

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(callerBound.compareTo(MAX_REQUEST_TIMEOUT) > 0 ? MAX_REQUEST_TIMEOUT : callerBound)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<CapturedBody> response;
        try {
            response = client.send(request, ignored -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES));
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new SummaryFailure(FailureCode.AI_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        } catch (java.io.IOException | RuntimeException transportFailure) {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }

        if (response.statusCode() == 429) {
            throw new SummaryFailure(FailureCode.AI_RATE_LIMITED,
                    retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
        if (response.body().oversized()) throw invalidOutput();

        try {
            JsonNode root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response.body().bytes());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!root.isObject() || !content.isTextual() || content.textValue().isBlank()) throw invalidOutput();
            return content.textValue();
        } catch (SummaryFailure failure) {
            throw failure;
        } catch (java.io.IOException malformed) {
            throw invalidOutput();
        }
    }

    private static void validate(String instructions, String input, int outputTokens, Duration timeout) {
        if (instructions == null || instructions.isBlank() || input == null || input.isBlank()
                || outputTokens < 1 || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
        if (instructions.getBytes(StandardCharsets.UTF_8).length > MAX_INSTRUCTION_BYTES
                || input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) throw invalidOutput();
    }

    private byte[] requestBody(String instructions, String input, int outputTokens) {
        try {
            return json.writeValueAsBytes(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "system", "content", instructions),
                            Map.of("role", "user", "content", input)),
                    "max_tokens", outputTokens,
                    "stream", false,
                    "response_format", Map.of("type", "json_object")));
        } catch (java.io.IOException serializationFailure) {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
    }

    private Integer retryAfter(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds > 0 ? (int) Math.min(3600, seconds) : null;
        } catch (NumberFormatException notSeconds) {
            try {
                long seconds = Duration.between(clock.instant(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds();
                return seconds > 0 ? (int) Math.min(3600, seconds) : null;
            } catch (java.time.DateTimeException invalidDate) {
                return null;
            }
        }
    }

    private static boolean isLoopbackTestEndpoint(URI endpoint) {
        return "http".equalsIgnoreCase(endpoint.getScheme()) && endpoint.getHost() != null
                && (endpoint.getHost().equals("127.0.0.1") || endpoint.getHost().equals("localhost")
                || endpoint.getHost().equals("::1"));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required.");
        return value;
    }

    private static SummaryFailure invalidOutput() { return new SummaryFailure(FailureCode.AI_INVALID_OUTPUT); }

    private record CapturedBody(byte[] bytes, boolean oversized) {}

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<CapturedBody> {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<CapturedBody> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean oversized;

        private BoundedBodySubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<CapturedBody> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - output.size()) {
                    oversized = true;
                    subscription.cancel();
                    body.complete(new CapturedBody(output.toByteArray(), true));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() {
            if (!oversized) body.complete(new CapturedBody(output.toByteArray(), false));
        }
    }
}
