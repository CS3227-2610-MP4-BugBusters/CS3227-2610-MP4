package mp4.platform;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import mp4.socllm.SocLlmContracts.Call;
import mp4.socllm.SocLlmContracts.RoleGateway;
import org.junit.jupiter.api.Test;

class DeveloperPromptRefinementTest {
    @Test
    void developerPromptPrioritizesChecksTiedToReportedSymptoms() {
        AtomicReference<Call> captured = new AtomicReference<>();
        RoleGateway gateway = call -> {
            captured.set(call);
            return "{\"steps\":[\"Inspect the login response\"]}";
        };
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, "test-api-key", "test-model");

        provider.suggest(UUID.randomUUID(), "Login button hangs",
                "Submitting valid credentials leaves the page unchanged.",
                List.of("Reproduce with valid credentials"));

        assertNotNull(captured.get());
        String prompt = captured.get().instructions().toLowerCase();
        assertTrue(prompt.matches("(?s).*(prioriti[sz]e|focus|base).*symptom.*"),
                "Prompt should prioritize concrete checks tied to the reported symptoms");
    }

    @Test
    void developerPromptSeparatesHypothesesFromEstablishedFacts() {
        AtomicReference<Call> captured = new AtomicReference<>();
        RoleGateway gateway = call -> {
            captured.set(call);
            return "{\"steps\":[\"Inspect the login response\"]}";
        };
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, "test-api-key", "test-model");

        provider.suggest(UUID.randomUUID(), "Login button hangs",
                "Submitting valid credentials leaves the page unchanged.",
                List.of("Reproduce with valid credentials"));

        assertNotNull(captured.get());
        String prompt = captured.get().instructions().toLowerCase();
        assertTrue(prompt.contains("hypoth") && prompt.contains("fact"),
                "Prompt should distinguish hypotheses from established facts");
    }

    @Test
    void refinementPreservesUntrustedInputSafetyOutputShapeAndStepBounds() {
        AtomicReference<Call> captured = new AtomicReference<>();
        RoleGateway gateway = call -> {
            captured.set(call);
            return "{\"steps\":[\"Inspect the login response\"]}";
        };
        SocLlmSuggestionProvider provider = new SocLlmSuggestionProvider(gateway, "test-api-key", "test-model");

        provider.suggest(UUID.randomUUID(), "Login button hangs",
                "Submitting valid credentials leaves the page unchanged.", List.of());

        assertNotNull(captured.get());
        String prompt = captured.get().instructions().toLowerCase();
        assertTrue(prompt.contains("untrusted") && prompt.contains("instructions"),
                "Report fields and saved steps must remain untrusted data, not instructions");
        assertTrue(prompt.contains("do not claim") && prompt.contains("perform actions"),
                "Suggestions must not claim resolution or perform application actions");
        assertTrue(prompt.contains("json object") && prompt.contains("steps")
                        && prompt.contains("nonempty array of strings"),
                "The shared-gateway JSON object response contract must be retained");
        assertTrue(prompt.contains("3 to 5") && prompt.contains("one line")
                        && prompt.contains("300 utf-8 bytes"),
                "The prompt must retain the count, line and byte bounds for each suggestion");
    }
}
