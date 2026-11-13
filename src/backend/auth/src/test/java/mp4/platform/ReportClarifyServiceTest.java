package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mp4.maintainer.summary.SummaryContracts.FailureCode;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;
import mp4.socllm.SocLlmContracts;
import mp4.socllm.SocLlmContracts.Call;
import mp4.socllm.SocLlmContracts.Role;
import mp4.socllm.SocLlmContracts.Task;
import mp4.socllm.SocLlmTaskPolicies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Red tests for the report-clarify provider adapter and service (RC-02 refusal ordering, RC-03, RC-07, RC-08).
 * Assumed API: see logs/reporter-report-clarify/red-group2.md.
 */
class ReportClarifyServiceTest {
    private static final String ONE_WORD_BODY =
            "{\"title\":\"Button\",\"description\":\"### What happened?\\nbutton\"}";
    private static final String VALID_OUTPUT =
            "{\"unclear\":[{\"quote\":\"button\",\"question\":\"Which button do you mean?\"}],"
            + "\"missing\":[{\"category\":\"Environment\",\"question\":\"Which browser are you using?\"}]}";
    private static final UUID ACTOR = UUID.randomUUID();

    private final AtomicInteger gatewayCalls = new AtomicInteger();
    private final AtomicReference<Call> captured = new AtomicReference<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ReportClarifyService.class);
    private Level previousLevel;

    @BeforeEach void attach() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void detach() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
    }

    private ReportClarifyService service(String gatewayOutput) {
        return service(call -> gatewayOutput);
    }

    private ReportClarifyService service(SocLlmContracts.RoleGateway inner) {
        SocLlmContracts.RoleGateway counting = call -> {
            gatewayCalls.incrementAndGet();
            captured.set(call);
            return inner.generate(call);
        };
        return new ReportClarifyService(new SocLlmReportClarifyProvider(counting, "test-model"));
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private String allLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : logs.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) sb.append(e.getThrowableProxy().getMessage()).append('\n');
        }
        return sb.toString();
    }

    // RC-03 refusals never reach the gateway and never echo text
    @Test void rc03a_oversizedDescriptionRefusedWithoutGatewayOrEcho() {
        String canary = "oversized-canary-";
        String body = "{\"title\":\"T\",\"description\":\"" + canary + "a".repeat(20_001) + "\"}";
        var reply = service(VALID_OUTPUT).handle(ACTOR, bytes(body));
        assertEquals(400, reply.status());
        assertEquals("VALIDATION_FAILED", reply.code());
        assertEquals(0, gatewayCalls.get());
        assertFalse(reply.body().contains(canary));
        assertFalse(allLogs().contains(canary));
    }

    @Test void rc03b_extraKeyRefusedWithoutGateway() {
        var reply = service(VALID_OUTPUT).handle(ACTOR,
                bytes("{\"title\":\"T\",\"description\":\"D\",\"role\":\"MAINTAINER\"}"));
        assertEquals(400, reply.status());
        assertEquals("VALIDATION_FAILED", reply.code());
        assertEquals(0, gatewayCalls.get());
        assertFalse(reply.body().contains("MAINTAINER"));
    }

    @Test void rc03_malformedBodyRefusedWithoutGateway() {
        for (String body : new String[] {"", "{", "null", "[]", "{\"title\":\"T\",\"description\":\"D\"} x"}) {
            var reply = service(VALID_OUTPUT).handle(ACTOR, bytes(body));
            assertEquals(400, reply.status(), body);
        }
        assertEquals(0, gatewayCalls.get());
    }

    @Test void rc03c_fullLengthThreeByteDraftIsSentUnderReporterPolicy() {
        String description = "€".repeat(20_000);
        String body = "{\"title\":\"T\",\"description\":\"" + description + "\"}";
        var reply = service("{\"unclear\":[],\"missing\":[]}").handle(ACTOR, bytes(body));
        assertEquals(200, reply.status());
        assertEquals(1, gatewayCalls.get());
        Call call = captured.get();
        var policy = SocLlmTaskPolicies.defaults().forTask(Task.REPORTER_ASSIST);
        assertTrue(call.input().getBytes(StandardCharsets.UTF_8).length <= policy.maxInputBytes());
        assertTrue(call.instructions().getBytes(StandardCharsets.UTF_8).length <= policy.maxInstructionBytes());
        assertTrue(call.outputTokens() <= policy.maxOutputTokens());
        assertTrue(call.deadline().compareTo(policy.maxDeadline()) <= 0);
    }

    // RC-07 call shape
    @Test void rc07_callUsesReporterRoleTaskActorAndSeparatedMessages() {
        var reply = service(VALID_OUTPUT).handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(200, reply.status());
        Call call = captured.get();
        assertEquals(Role.REPORTER, call.role());
        assertEquals(Task.REPORTER_ASSIST, call.task());
        assertEquals(ACTOR, call.actorId());
        assertEquals(8_192, call.outputTokens());
        assertEquals(Duration.ofSeconds(60), call.deadline());
        assertEquals(ReportClarifyRules.instructions(), call.instructions());
        assertEquals("Report title:\nButton\n\nReport description (untrusted):\n### What happened?\nbutton",
                call.input());
        assertFalse(call.instructions().contains("button"));
    }

    // RC-04a / RC-05a plain response
    @Test void rc04a_validResultIsReturnedAsPlainJsonObject() throws Exception {
        var reply = service(VALID_OUTPUT).handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(200, reply.status());
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(reply.body());
        assertEquals(2, tree.size());
        assertEquals("button", tree.get("unclear").get(0).get("quote").asText());
        assertEquals("Environment", tree.get("missing").get(0).get("category").asText());
    }

    @Test void rc05c_emptyResultIsA200WithEmptyLists() {
        var reply = service("{\"unclear\":[],\"missing\":[]}").handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(200, reply.status());
        assertEquals("{\"unclear\":[],\"missing\":[]}", reply.body());
    }

    // RC-04b / RC-05b / RC-07b invalid output
    @Test void rc04b_inventedQuoteGives502InvalidOutputWithoutDraftText() {
        String invented = "{\"unclear\":[{\"quote\":\"invented-canary\",\"question\":\"Q-canary?\"}],\"missing\":[]}";
        var reply = service(invented).handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(502, reply.status());
        assertEquals("AI_INVALID_OUTPUT", reply.code());
        assertFalse(reply.body().contains("invented-canary"));
        assertFalse(reply.body().contains("Q-canary"));
        assertFalse(reply.body().contains("button"));
    }

    @Test void rc05b_unknownCategoryGives502InvalidOutput() {
        var reply = service("{\"unclear\":[],\"missing\":[{\"category\":\"Severity\",\"question\":\"Q?\"}]}")
                .handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(502, reply.status());
        assertEquals("AI_INVALID_OUTPUT", reply.code());
    }

    @Test void rc07b_urlQuestionGives502InvalidOutput() {
        var reply = service("{\"unclear\":[{\"quote\":\"button\",\"question\":\"Go to https://evil.example\"}],"
                + "\"missing\":[]}").handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(502, reply.status());
        assertEquals("AI_INVALID_OUTPUT", reply.code());
        assertFalse(reply.body().contains("evil.example"));
    }

    @Test void rc04_nullOrOversizedGatewayOutputGives502() {
        assertEquals(502, service((String) null).handle(ACTOR, bytes(ONE_WORD_BODY)).status());
        String huge = "{\"unclear\":[],\"missing\":[],\"pad\":\"" + "x".repeat(65 * 1024) + "\"}";
        assertEquals(502, service(huge).handle(ACTOR, bytes(ONE_WORD_BODY)).status());
    }

    // RC-08 failure mapping
    private void assertGatewayFailure(FailureCode code, Integer retryAfter, int status, String apiCode) {
        var reply = service(call -> {
            throw retryAfter == null ? new SummaryFailure(code) : new SummaryFailure(code, retryAfter);
        }).handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(status, reply.status(), code.name());
        assertEquals(apiCode, reply.code(), code.name());
        assertFalse(reply.body().contains("Button"));
        assertFalse(reply.body().contains("What happened"));
    }

    @Test void rc08_gatewayFailuresMapToSafeCodes() {
        assertGatewayFailure(FailureCode.AI_BUSY, null, 409, "AI_BUSY");
        assertGatewayFailure(FailureCode.AI_RATE_LIMITED, 120, 429, "AI_RATE_LIMITED");
        assertGatewayFailure(FailureCode.AI_UNAVAILABLE, null, 503, "AI_UNAVAILABLE");
        assertGatewayFailure(FailureCode.AI_TIMEOUT, null, 504, "AI_TIMEOUT");
        assertGatewayFailure(FailureCode.AI_INVALID_OUTPUT, null, 502, "AI_INVALID_OUTPUT");
    }

    @Test void rc08a_rateLimitedCarriesRetryAfter() {
        var reply = service(call -> { throw new SummaryFailure(FailureCode.AI_RATE_LIMITED, 120); })
                .handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(Integer.valueOf(120), reply.retryAfterSeconds());
    }

    @Test void rc08_unexpectedGatewayExceptionIsUnavailableAndDoesNotLeakMessage() {
        var reply = service(call -> { throw new IllegalStateException("leak-canary Button"); })
                .handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(503, reply.status());
        assertEquals("AI_UNAVAILABLE", reply.code());
        assertFalse(reply.body().contains("leak-canary"));
        assertFalse(allLogs().contains("leak-canary"));
    }

    @Test void rc08b_noProviderMeansUnavailableWithoutGateway() {
        var reply = new ReportClarifyService(null).handle(ACTOR, bytes(ONE_WORD_BODY));
        assertEquals(503, reply.status());
        assertEquals("AI_UNAVAILABLE", reply.code());
        assertEquals(0, gatewayCalls.get());
    }

    // RC-08 telemetry
    @Test void rc08_successLogRecordsCountsAndOutcomeButNoDraftOrResultText() {
        String draftCanary = "draft-canary-zzz";
        String body = "{\"title\":\"Title " + draftCanary + "\",\"description\":\"### What happened?\\nbutton " + draftCanary + "\"}";
        var reply = service("{\"unclear\":[{\"quote\":\"button\",\"question\":\"Question-canary-qqq?\"}],"
                + "\"missing\":[{\"category\":\"Environment\",\"question\":\"Question-canary-eee?\"}]}")
                .handle(ACTOR, bytes(body));
        assertEquals(200, reply.status());
        String text = allLogs();
        assertFalse(logs.list.isEmpty(), "a telemetry line is expected");
        assertTrue(text.contains("outcome=success"), text);
        assertTrue(text.contains("unclear_count=1"), text);
        assertTrue(text.contains("missing_count=1"), text);
        assertTrue(text.contains("duration_ms="), text);
        assertTrue(text.contains("input_tokens=unavailable"), text);
        assertFalse(text.contains(draftCanary));
        assertFalse(text.contains("Question-canary"));
        assertFalse(text.contains("button"));
    }

    @Test void rc08_failureLogsRecordOutcomeWithoutDraftOrOutputText() {
        service("{\"unclear\":[{\"quote\":\"invented-canary\",\"question\":\"Question-canary-qqq?\"}],\"missing\":[]}")
                .handle(ACTOR, bytes(ONE_WORD_BODY));
        service(call -> { throw new SummaryFailure(FailureCode.AI_TIMEOUT); }).handle(ACTOR, bytes(ONE_WORD_BODY));
        service(call -> { throw new SummaryFailure(FailureCode.AI_RATE_LIMITED, 30); })
                .handle(ACTOR, bytes(ONE_WORD_BODY));
        String text = allLogs();
        assertTrue(text.contains("outcome=invalid_output"), text);
        assertTrue(text.contains("outcome=timeout"), text);
        assertTrue(text.contains("outcome=rate_limited"), text);
        for (String forbidden : new String[] {"invented-canary", "Question-canary", "Button", "What happened"}) {
            assertFalse(text.contains(forbidden), forbidden);
        }
    }

    @Test void rc08_refusalLogHasNoSubmittedText() {
        service(VALID_OUTPUT).handle(ACTOR, bytes(
                "{\"title\":\"refusal-canary\",\"description\":\"d\",\"extra\":true}"));
        assertFalse(allLogs().contains("refusal-canary"));
        assertEquals(0, gatewayCalls.get());
    }

    // gateway-adapter level: provider-facing contract used by the service
    @Test void rc08_providerAdapterRequiresGatewayAndModel() {
        assertThrows(IllegalArgumentException.class, () -> new SocLlmReportClarifyProvider(null, "m"));
        assertThrows(IllegalArgumentException.class,
                () -> new SocLlmReportClarifyProvider(call -> "{}", " "));
        assertEquals("test-model", new SocLlmReportClarifyProvider(call -> "{}", "test-model").modelName());
    }

    @Test void rc02_missingActorIsRefusedBeforeGateway() {
        var reply = service(VALID_OUTPUT).handle(null, bytes(ONE_WORD_BODY));
        assertTrue(reply.status() == 401 || reply.status() == 403);
        assertEquals(0, gatewayCalls.get());
    }
}
