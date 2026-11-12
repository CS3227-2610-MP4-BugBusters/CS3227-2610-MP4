package mp4.maintainer.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import mp4.maintainer.summary.SummaryContracts.CacheKey;
import mp4.maintainer.summary.SummaryContracts.GatewayRequest;
import mp4.maintainer.workflow.MaintainerContracts.Status;
import org.junit.jupiter.api.Test;

import static mp4.maintainer.summary.SummaryFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Focused regression for report facts carried in free-text descriptions. */
class SummaryDescriptionGroundingTest {
    @Test
    void forwardsDescriptionFactsWithoutNullPlaceholdersAndChecksAllEvidenceBeforeMissingClaims() throws Exception {
        var fixture = new SummaryFixture();
        String description = "Chrome 130 on Windows 11; selecting Save reproduces it; expected a confirmation.";
        var report = new mp4.maintainer.summary.SummaryContracts.ReportInput(id(41), 3, Status.IN_PROGRESS,
                mp4.maintainer.workflow.MaintainerContracts.Priority.HIGH, id(9041), NOW.minusSeconds(20), NOW,
                "Save fails", description, null, null, null, null, List.of());
        fixture.snapshot.set(snapshot(List.of(report)));

        var result = fixture.service().generate(fixture.actor,
                new mp4.maintainer.summary.SummaryContracts.Filters(null, null, null), true);

        GatewayRequest request = fixture.requests.get(0);
        JsonNode context = new ObjectMapper().readTree(request.reports().value()).get(0);
        assertEquals(description, context.get("description").textValue());
        for (String absent : List.of("reproductionSteps", "expectedBehavior", "actualBehavior", "environment")) {
            assertFalse(context.has(absent), "absent optional field should be omitted: " + absent);
        }
        assertEquals("Save fails", context.get("title").textValue());
        assertEquals(3, context.get("version").intValue());

        String instructions = request.instructions().value().toLowerCase(java.util.Locale.ROOT);
        assertTrue(instructions.contains("all supplied text") || instructions.contains("all supplied fields"),
                "trusted instructions must direct the model to inspect supplied evidence");
        assertTrue(instructions.contains("before") && instructions.contains("missing"),
                "missing information should be called only after checking supplied evidence");
        assertTrue(instructions.contains("concise"), "findings should be concise");
        assertEquals("MNT-SUMMARY-1", result.promptVersion(),
                "the client wire prompt version remains stable");

        CacheKey key = fixture.keys.get(0);
        assertNotEquals("MNT-CONTEXT-1", key.contextPolicyVersion(),
                "changed prompt/context policy must use a new cache context revision");
    }
}
