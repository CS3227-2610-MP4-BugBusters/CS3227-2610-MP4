package mp4.socllm;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import mp4.maintainer.summary.SummaryContracts.*;

class SocLlmHttpProviderTest {
    private HttpServer server;

    private SocLlmHttpProvider provider(java.util.function.Consumer<HttpServer> configure) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        configure.accept(server);server.start();
        URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat");
        return new SocLlmHttpProvider(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper(),endpoint,"dummy-secret-value","test-model",Clock.systemUTC());
    }
    private static void reply(com.sun.net.httpserver.HttpExchange exchange,int status,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
        try(var output=exchange.getResponseBody()){output.write(bytes);}
    }
    @AfterEach void close(){if(server!=null)server.stop(0);}

    @Test void sendsInstructionsAndUntrustedInputSeparatelyAndParsesOnlyProviderContent() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var provider=provider(s->s.createContext("/chat",exchange->{
            calls.incrementAndGet();String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(exchange.getRequestHeaders().getFirst("Authorization").equals("Bearer dummy-secret-value"));
            assertFalse(body.contains("dummy-secret-value"));
            assertTrue(body.contains("trusted rules")&&body.contains("untrusted report"));
            reply(exchange,200,"{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}");
        }));
        assertEquals("{\"ok\":true}",provider.generate("trusted rules","untrusted report",100,Duration.ofSeconds(2)));
        assertEquals(1,calls.get());
    }

    @Test void paddedAndCanonicalKeysSendTheSameBearerIdentityAndInvalidTokensAreRejected() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        java.util.List<String> authorization=new java.util.concurrent.CopyOnWriteArrayList<>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{
            calls.incrementAndGet();
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange,200,"{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });
        server.start();
        URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat");
        HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var padded=new SocLlmHttpProvider(client,new ObjectMapper(),endpoint,"  shared-token== \t","test-model",Clock.systemUTC());
        var canonical=new SocLlmHttpProvider(client,new ObjectMapper(),endpoint,"shared-token==","test-model",Clock.systemUTC());

        assertEquals("ok",padded.generate("rules","data",20,Duration.ofSeconds(2)));
        assertEquals("ok",canonical.generate("rules","data",20,Duration.ofSeconds(2)));
        assertEquals(java.util.List.of("Bearer shared-token==","Bearer shared-token=="),authorization);
        assertEquals(2,calls.get());
        assertThrows(IllegalArgumentException.class,()->new SocLlmHttpProvider(client,new ObjectMapper(),endpoint,
                "shared token==","test-model",Clock.systemUTC()));
    }

    @Test void redirectAndServerFailureAreNotFollowedOrRetriedAndFailuresStaySafe() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var provider=provider(s->s.createContext("/chat",exchange->{calls.incrementAndGet();exchange.getResponseHeaders().add("Location","/other");reply(exchange,302,"secret provider detail");}));
        SummaryFailure failure=assertThrows(SummaryFailure.class,()->provider.generate("rules","data",20,Duration.ofSeconds(2)));
        assertEquals(FailureCode.AI_UNAVAILABLE,failure.failureCode());assertFalse(failure.getMessage().contains("secret"));assertEquals(1,calls.get());
    }

    @Test void rateLimitRetryAfterIsClampedAndMalformedProviderJsonIsInvalidOutput() throws Exception {
        var limited=provider(s->s.createContext("/chat",exchange->{exchange.getResponseHeaders().add("Retry-After","999999");reply(exchange,429,"upstream secret");}));
        SummaryFailure rate=assertThrows(SummaryFailure.class,()->limited.generate("rules","data",20,Duration.ofSeconds(2)));
        assertEquals(FailureCode.AI_RATE_LIMITED,rate.failureCode());assertEquals(3600,rate.retryAfterSeconds());
        server.stop(0);server=null;
        var malformed=provider(s->s.createContext("/chat",exchange->reply(exchange,200,"not json")));
        SummaryFailure invalid=assertThrows(SummaryFailure.class,()->malformed.generate("rules","data",20,Duration.ofSeconds(2)));
        assertEquals(FailureCode.AI_INVALID_OUTPUT,invalid.failureCode());
    }

    @Test void responseAndRequestBodiesHaveExplicitByteBounds() throws Exception {
        var large=provider(s->s.createContext("/chat",exchange->reply(exchange,200,"x".repeat(70_000))));
        assertEquals(FailureCode.AI_INVALID_OUTPUT,assertThrows(SummaryFailure.class,()->large.generate("rules","data",20,Duration.ofSeconds(2))).failureCode());
        assertEquals(FailureCode.AI_INVALID_OUTPUT,assertThrows(SummaryFailure.class,()->large.generate("rules","x".repeat(25_000),20,Duration.ofSeconds(2))).failureCode());
    }
}
