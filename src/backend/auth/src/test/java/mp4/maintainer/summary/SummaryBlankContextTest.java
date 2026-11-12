package mp4.maintainer.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import mp4.maintainer.summary.SummaryContracts.GatewayRequest;
import mp4.maintainer.summary.SummaryContracts.ReportInput;
import mp4.maintainer.workflow.MaintainerContracts.Priority;
import mp4.maintainer.workflow.MaintainerContracts.Status;
import org.junit.jupiter.api.Test;

import static mp4.maintainer.summary.SummaryFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for blank optional fields stored on real reports. */
class SummaryBlankContextTest {
    private static final List<String> OPTIONAL_FIELDS = List.of(
            "reproductionSteps", "expectedBehavior", "actualBehavior", "environment");

    @Test
    void omitsMigrationDefaultBlanksAndWhitespaceButKeepsDescriptionFacts() throws Exception {
        var fixture = new SummaryFixture();
        String description = "Chrome 130 on Windows 11; selecting Save reproduces it; expected a confirmation.";
        ReportInput storedDefaults = new ReportInput(id(57), 2, Status.IN_PROGRESS, Priority.HIGH,
                id(9057), NOW.minusSeconds(20), NOW, "Save fails", description,
                "", "", "", "  ", List.of());
        fixture.snapshot.set(snapshot(List.of(storedDefaults)));

        fixture.service().generate(fixture.actor,
                new SummaryContracts.Filters(null, null, null), true);

        GatewayRequest request = fixture.requests.get(0);
        JsonNode context = new ObjectMapper().readTree(request.reports().value()).get(0);
        assertEquals(description, context.get("description").textValue());
        for (String absent : OPTIONAL_FIELDS) {
            assertFalse(context.has(absent), "blank optional field should be omitted: " + absent);
        }
    }

    @Test
    void preservesPopulatedStructuredFieldsIncludingInternalWhitespace() throws Exception {
        var fixture = new SummaryFixture();
        String reproduction = "  Open the report\n  Select Save  ";
        String expected = "  A confirmation\n  appears  ";
        String actual = "  No confirmation\n  appears  ";
        String environment = "  Chrome 130\n  Windows 11  ";
        ReportInput populated = new ReportInput(id(58), 3, Status.IN_PROGRESS, Priority.HIGH,
                id(9058), NOW.minusSeconds(20), NOW, "Save fails", "Save action fails.",
                reproduction, expected, actual, environment, List.of());
        fixture.snapshot.set(snapshot(List.of(populated)));

        fixture.service().generate(fixture.actor,
                new SummaryContracts.Filters(null, null, null), true);

        JsonNode context = new ObjectMapper().readTree(fixture.requests.get(0).reports().value()).get(0);
        assertEquals(reproduction, context.get("reproductionSteps").textValue());
        assertEquals(expected, context.get("expectedBehavior").textValue());
        assertEquals(actual, context.get("actualBehavior").textValue());
        assertEquals(environment, context.get("environment").textValue());
    }
}
