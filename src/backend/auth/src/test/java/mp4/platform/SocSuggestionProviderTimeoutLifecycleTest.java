package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SocSuggestionProviderTimeoutLifecycleTest {
    private static final String PREFIX = "{\"choices\":[{\"message\":{\"content\":\"[\\\"step\\\"]\"}}]}";

    @Test @Timeout(5)
    void timeoutCancelsUnderlyingOperationAndReleasesCapacityOnlyAfterCancellation() throws Exception {
        FakeHttpClient client = new FakeHttpClient(PREFIX);
        var adapter = new SocSuggestionProvider(client, URI.create("http://fake.invalid"),
                "synthetic-test-token", "team-test-model", Duration.ofMillis(250));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var timedOutCall = executor.submit(() -> adapter.suggest("title", "description"));
            assertTrue(client.firstResponseStarted.await(1, TimeUnit.SECONDS),
                    "fake provider must deliver headers and a streaming chunk");

            IllegalStateException busy = assertThrows(IllegalStateException.class,
                    () -> adapter.suggest("second", "request"));
            assertTrue(busy.getMessage().contains("busy"),
                    "concurrent request must be rejected while the first operation is active");

            ExecutionException timeout = assertThrows(ExecutionException.class,
                    () -> timedOutCall.get(1, TimeUnit.SECONDS));
            assertTrue(timeout.getCause().toString().contains("timed out"));
            assertTrue(client.cancellationAcknowledged.await(1, TimeUnit.SECONDS),
                    "deadline must cancel the HTTP operation");
            assertTrue(client.upstreamCancelled.get(),
                    "operation cancellation must stop its streaming response subscription");
            assertTrue(client.firstOperation.isCancelled(), "cancelled transport future must be terminal");

            assertEquals("[\"step\"]", adapter.suggest("title", "description").rawOutput());
            assertEquals(2, client.requestCount.get());
        } finally {
            client.stopFirstOperation();
            executor.shutdownNow();
        }
    }

    @Test void rejectsProviderEnvelopeWithTrailingGarbage() {
        FakeHttpClient client = new FakeHttpClient(PREFIX + " trailing-garbage");
        var adapter = new SocSuggestionProvider(client, URI.create("http://fake.invalid"),
                "synthetic-test-token", "team-test-model", Duration.ofSeconds(2));

        assertThrows(RuntimeException.class, () -> adapter.suggest("title", "description"));
    }

    private static final class FakeHttpClient extends HttpClient {
        private final String firstBody;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final CountDownLatch firstResponseStarted = new CountDownLatch(1);
        private final CountDownLatch cancellationAcknowledged = new CountDownLatch(1);
        private final AtomicBoolean upstreamCancelled = new AtomicBoolean();
        private final AtomicReference<java.util.concurrent.Flow.Subscription> firstSubscription = new AtomicReference<>();
        private final TrackedFuture<HttpResponse<byte[]>> firstOperation = new TrackedFuture<>(() -> {
            var subscription = firstSubscription.get();
            if (subscription != null) subscription.cancel();
            upstreamCancelled.set(true);
            cancellationAcknowledged.countDown();
        });

        private FakeHttpClient(String firstBody) {
            this.firstBody = firstBody;
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException { throw new UnsupportedOperationException(); }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            int number = requestCount.incrementAndGet();
            var subscriber = handler.apply(new FakeResponseInfo());
            CompletableFuture<HttpResponse<T>> operation;
            if (number == 1) {
                operation = (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) firstOperation;
                // The server has returned headers and a body chunk but withholds completion.
                @SuppressWarnings("unchecked")
                var sub = (java.util.concurrent.Flow.Subscriber<? super List<ByteBuffer>>) subscriber;
                var subscription = new java.util.concurrent.Flow.Subscription() {
                    @Override public void request(long count) { }
                    @Override public void cancel() { upstreamCancelled.set(true); }
                };
                firstSubscription.set(subscription);
                subscriber.onSubscribe(subscription);
                sub.onNext(List.of(ByteBuffer.wrap("{\"choices\":[".getBytes(StandardCharsets.UTF_8))));
                firstResponseStarted.countDown();
            } else {
                operation = new CompletableFuture<>();
                subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                    @Override public void request(long count) { }
                    @Override public void cancel() { }
                });
                @SuppressWarnings("unchecked")
                var sub = (java.util.concurrent.Flow.Subscriber<? super List<ByteBuffer>>) subscriber;
                sub.onNext(List.of(ByteBuffer.wrap(firstBody.getBytes(StandardCharsets.UTF_8))));
                sub.onComplete();
                subscriber.getBody().whenComplete((body, failure) -> {
                    if (failure != null) operation.completeExceptionally(failure);
                    else operation.complete(new FakeResponse<>(request, body));
                });
            }
            return operation;
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }

        private void stopFirstOperation() {
            if (!firstOperation.isDone()) firstOperation.completeExceptionally(new IOException("test cleanup"));
        }
    }

    private static final class TrackedFuture<T> extends CompletableFuture<T> {
        private final Runnable cancelAction;
        private TrackedFuture(Runnable cancelAction) { this.cancelAction = cancelAction; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            cancelAction.run();
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class FakeResponseInfo implements HttpResponse.ResponseInfo {
        @Override public int statusCode() { return 200; }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private record FakeResponse<T>(HttpRequest request, T body) implements HttpResponse<T> {
        @Override public int statusCode() { return 200; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
