package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import mp4.maintainer.summary.SummaryContracts.FailureCode;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;
import mp4.socllm.SocLlmContracts;
import mp4.socllm.SocLlmContracts.Call;
import mp4.socllm.SocLlmContracts.Role;
import mp4.socllm.SocLlmContracts.Task;
import org.junit.jupiter.api.Test;

class SocLlmSuggestionProviderTest {
    @Test void rejectsEscapedCredentialInIgnoredExtraField() {
        String credential = "synthetic-gateway-key-123";
        String escaped = "synthetic" + Character.toString(92) + "u002d" + "gateway-key-123";
        SocLlmContracts.RoleGateway gateway = call ->
                "{\"steps\":[\"Inspect logs\"],\"metadata\":{\"value\":\"" + escaped + "\"}}";
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, credential, "test-model");
        SuggestionProvider.Failure failure = assertThrows(SuggestionProvider.Failure.class,
                () -> provider.suggest(UUID.randomUUID(), "Title", "Description", List.of()));
        assertEquals(SuggestionProvider.FailureKind.INVALID, failure.kind());
        assertFalse(failure.getMessage().contains(credential));
    }
    @Test void routesSuggestionsThroughDeveloperGatewayWithTrustedActorAndSavedContext() {
        UUID actorId = UUID.randomUUID();
        AtomicReference<Call> captured = new AtomicReference<>();
        SocLlmContracts.RoleGateway gateway = call -> {
            captured.set(call);
            return "{\"steps\":[\"Inspect the authentication response\"]}";
        };
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, "test-api-key", "test-model");

        SuggestionProvider.ProviderResponse response = provider.suggest(
                actorId, "Login button hangs", "Submitting valid credentials leaves the page unchanged.",
                List.of("Reproduce with valid credentials", "Inspect the browser console"));

        Call call = captured.get();
        assertNotNull(call);
        assertEquals(Role.DEVELOPER, call.role());
        assertEquals(actorId, call.actorId());
        assertEquals(Task.DEVELOPER_ASSIST, call.task());
        assertEquals(384, call.outputTokens());
        assertEquals(java.time.Duration.ofSeconds(15), call.deadline());
        assertTrue(call.instructions().toLowerCase().contains("untrusted"));
        assertTrue(call.input().contains("Login button hangs"));
        assertTrue(call.input().contains("Submitting valid credentials leaves the page unchanged."));
        assertTrue(call.input().contains("Reproduce with valid credentials"));
        assertTrue(call.input().contains("Inspect the browser console"));
        assertEquals("[\"Inspect the authentication response\"]", response.rawOutput());
    }

    @Test void acceptsValidStepsAlongsideUnrelatedProviderEnvelopeFields() {
        SocLlmContracts.RoleGateway gateway = call ->
                "{\"response\":\"provider metadata\",\"steps\":[\"Inspect the authentication response\"]}";
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, "test-api-key", "test-model");

        SuggestionProvider.ProviderResponse response = provider.suggest(
                UUID.randomUUID(), "Login button hangs", "Submitting credentials leaves the page unchanged.",
                List.of("Reproduce the issue"));

        assertEquals("[\"Inspect the authentication response\"]", response.rawOutput());
    }

    @Test void stillRejectsCredentialFoundInAnAdditionalEnvelopeField() {
        String credential = "synthetic-gateway-key-123";
        SocLlmContracts.RoleGateway gateway = call ->
                "{\"steps\":[\"Inspect the response\"],\"metadata\":{\"debug\":\"" + credential + "\"}}";
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, credential, "test-model");

        SuggestionProvider.Failure failure = assertThrows(SuggestionProvider.Failure.class,
                () -> provider.suggest(UUID.randomUUID(), "Title", "Description", List.of("Saved step")));
        assertEquals(SuggestionProvider.FailureKind.INVALID, failure.kind());
        assertFalse(failure.getMessage().contains(credential));
    }

    @Test void stillRejectsMalformedDuplicateAndTrailingEnvelopeContent() {
        assertInvalidOutput("{\"steps\":[\"Inspect the response\"]");
        assertInvalidOutput("{\"steps\":[\"First\"],\"steps\":[\"Second\"]}");
        assertInvalidOutput("{\"steps\":[\"Inspect the response\"]} trailing");
    }

    @Test void stillRejectsProviderResponsesAboveTheByteLimit() {
        String output = "{\"steps\":[\"Inspect the response\"],\"metadata\":\"" + "x".repeat(65 * 1024) + "\"}";
        assertInvalidOutput(output);
    }

    @Test void mapsSharedGatewayFailuresToSafeProviderCategories() {
        assertFailure(FailureCode.AI_TIMEOUT, SuggestionProvider.FailureKind.TIMEOUT);
        assertFailure(FailureCode.AI_BUSY, SuggestionProvider.FailureKind.BUSY);
        assertFailure(FailureCode.AI_RATE_LIMITED, SuggestionProvider.FailureKind.RATE_LIMITED);
        assertFailure(FailureCode.AI_UNAVAILABLE, SuggestionProvider.FailureKind.UNAVAILABLE);
        assertFailure(FailureCode.AI_INVALID_OUTPUT, SuggestionProvider.FailureKind.INVALID);
    }

    @Test void rejectsGatewayOutputThatReflectsItsConfiguredCredential() {
        String credential = "synthetic-gateway-key-123";
        SocLlmContracts.RoleGateway gateway = call ->
                "{\"steps\":[\"Reflected credential: " + credential + "\"]}";
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, credential, "test-model");

        SuggestionProvider.Failure failure = assertThrows(SuggestionProvider.Failure.class,
                () -> provider.suggest(UUID.randomUUID(), "Title", "Description", List.of("Saved step")));
        assertEquals(SuggestionProvider.FailureKind.INVALID, failure.kind());
        assertFalse(failure.getMessage().contains(credential));
    }

    @Test void rejectsGatewayCredentialReflectedThroughJsonUnicodeEscapes() {
        String credential = "synthetic-gateway-key-123";
        String unicodeEscape = Character.toString(92) + "u002d";
        String escapedCredential = "synthetic" + unicodeEscape + "gateway" + unicodeEscape
                + "key" + unicodeEscape + "123";
        SocLlmContracts.RoleGateway gateway = call ->
                "{\"steps\":[\"Reflected credential: " + escapedCredential + "\"]}";
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, credential, "test-model");

        SuggestionProvider.Failure failure = assertThrows(SuggestionProvider.Failure.class,
                () -> provider.suggest(UUID.randomUUID(), "Title", "Description", List.of("Saved step")));
        assertEquals(SuggestionProvider.FailureKind.INVALID, failure.kind());
        assertFalse(failure.getMessage().contains(credential));
    }

    private static void assertFailure(FailureCode code, SuggestionProvider.FailureKind expected) {
        SocLlmContracts.RoleGateway gateway = call -> {
            throw new SummaryFailure(code);
        };
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, "test-api-key", "test-model");

        SuggestionProvider.Failure failure = assertThrows(SuggestionProvider.Failure.class,
                () -> provider.suggest(UUID.randomUUID(), "Title", "Description", List.of("Saved step")));
        assertEquals(expected, failure.kind());
        assertFalse(failure.getMessage().contains("Title"));
        assertFalse(failure.getMessage().contains("Description"));
    }

    private static void assertInvalidOutput(String output) {
        SocLlmContracts.RoleGateway gateway = call -> output;
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, "test-api-key", "test-model");
        SuggestionProvider.Failure failure = assertThrows(SuggestionProvider.Failure.class,
                () -> provider.suggest(UUID.randomUUID(), "Title", "Description", List.of("Saved step")));
        assertEquals(SuggestionProvider.FailureKind.INVALID, failure.kind());
        assertFalse(failure.getMessage().contains("Title"));
        assertFalse(failure.getMessage().contains("Description"));
    }
}
