package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Owner decision 2026-11-13 (b): RC-07b drops only the screened item; RC-07c hidden-character rules. */
class ReportClarifyDropScreenedTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ReportClarifyRules.Draft DRAFT =
            new ReportClarifyRules.Draft("Button", "### What happened?\nbutton  stops");

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static Map<String, String> u(String quote, String question) {
        return Map.of("quote", quote, "question", question);
    }

    private static Map<String, String> m(String category, String question) {
        return Map.of("category", category, "question", question);
    }

    private static String out(List<Map<String, String>> unclear, List<Map<String, String>> missing) throws Exception {
        return JSON.writeValueAsString(Map.of("unclear", unclear, "missing", missing));
    }

    private static void rejectedWithValidSibling(String badQuestion) throws Exception {
        String inUnclear = out(List.of(u("button", "Which button?"), u("stops", badQuestion)), List.of());
        String inMissing = out(List.of(u("button", "Which button?")), List.of(m("Summary", badQuestion)));
        assertThrows(IllegalArgumentException.class, () -> ReportClarifyRules.validateOutput(inUnclear, DRAFT),
                "unclear " + badQuestion);
        assertThrows(IllegalArgumentException.class, () -> ReportClarifyRules.validateOutput(inMissing, DRAFT),
                "missing " + badQuestion);
    }

    // RC-07b
    @Test void rc07b_screenedUnclearQuestionDropsOnlyItsItem() throws Exception {
        var result = ReportClarifyRules.validateOutput(out(List.of(
                u("button", "Did you clear your browser cookies?"), u("stops", "Stops how?")), List.of()), DRAFT);
        assertEquals(1, result.unclear().size());
        assertEquals("stops", result.unclear().get(0).quote());
        assertEquals("Stops how?", result.unclear().get(0).question());
    }

    @Test void rc07b_screenedMissingQuestionDropsOnlyItsItem() throws Exception {
        var result = ReportClarifyRules.validateOutput(out(List.of(),
                List.of(m("Summary", "What is your password?"), m("Environment", "Which browser?"),
                        m("Frequency", "Open https://evil.example"), m("Actual behavior", "Mail a@b.example"))), DRAFT);
        assertEquals(1, result.missing().size());
        assertEquals("Environment", result.missing().get(0).category());
        assertTrue(result.unclear().isEmpty());
    }

    @Test void rc07b_dropsAcrossBothListsKeepingValidOnesInOrder() throws Exception {
        var result = ReportClarifyRules.validateOutput(out(
                List.of(u("button", "Which button?"), u("stops", "Send the token"), u("What happened", "Meaning?")),
                List.of(m("Summary", "Visit www.evil.example"), m("Frequency", "How often?"))), DRAFT);
        assertEquals(2, result.unclear().size());
        assertEquals("button", result.unclear().get(0).quote());
        assertEquals("What happened", result.unclear().get(1).quote());
        assertEquals(1, result.missing().size());
        assertEquals("Frequency", result.missing().get(0).category());
    }

    @Test void rc07b_cookiesAnd2faQuestionsNoLongerFailResultWithOtherValidItems() throws Exception {
        var result = ReportClarifyRules.validateOutput(out(List.of(u("button", "Which button do you mean?")),
                List.of(m("Environment", "Did you clear your browser cookies?"),
                        m("Frequency", "Is 2FA enabled when it happens?"), m("Summary", "What is the goal?"))), DRAFT);
        assertEquals(1, result.unclear().size());
        assertEquals(1, result.missing().size());
        assertEquals("Summary", result.missing().get(0).category());
    }

    @Test void rc07b_droppedItemsDoNotAppearInResultJson() throws Exception {
        var result = ReportClarifyRules.validateOutput(out(
                List.of(u("button", "Paste your API key"), u("stops", "Stops how?")), List.of()), DRAFT);
        assertEquals(1, JSON.readTree(result.toJson()).get("unclear").size());
        assertFalse(result.toJson().contains("API key"));
    }

    @Test void rc07b_everyItemDroppedIsInvalidOutputNotAnEmptyResult() throws Exception {
        String allUnclear = out(List.of(u("button", "Send the token"), u("stops", "Open http://evil.example")),
                List.of());
        String mixedLists = out(List.of(u("button", "What is your password?")),
                List.of(m("Summary", "Mail a@b.example"), m("Environment", "Did you clear your cookies?")));
        for (String output : new String[] {allUnclear, mixedLists}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ReportClarifyRules.validateOutput(output, DRAFT));
            assertEquals("Invalid report check output", failure.getMessage());
        }
    }

    @Test void rc07b_genuinelyEmptyResultStaysValid() throws Exception {
        var result = ReportClarifyRules.validateOutput(out(List.of(), List.of()), DRAFT);
        assertTrue(result.unclear().isEmpty() && result.missing().isEmpty());
    }

    @Test void rc07b_ordinaryWordsAreKeptWhileCredentialRequestIsDropped() throws Exception {
        var result = ReportClarifyRules.validateOutput(out(
                List.of(u("button", "Which tokenizer rule applies?"), u("stops", "Share your credentials")),
                List.of()), DRAFT);
        assertEquals(1, result.unclear().size());
        assertEquals("button", result.unclear().get(0).quote());
    }

    // RC-07c: invisible characters cannot hide a screened word, and invalid characters reject the whole result.
    @Test void rc07c_securityReviewerBypassStringsDoNotSurvive() throws Exception {
        for (String bypass : new String[] {"pass" + cp(0x034F) + "word", "to" + cp(0xFE0F) + "ken"}) {
            rejectedWithValidSibling("What is your " + bypass + "?");
            String output = out(List.of(u("button", "What is your " + bypass + "?")),
                    List.of(m("Summary", "What is the goal?")));
            assertThrows(IllegalArgumentException.class, () -> ReportClarifyRules.validateOutput(output, DRAFT),
                    bypass);
        }
    }

    @Test void rc07c_defaultIgnorableAndFormatCharactersRejectWholeResult() throws Exception {
        int[] hidden = {0x034F, 0xFE00, 0xFE0F, 0x115F, 0x1160, 0x3164, 0xFFA0, 0x200B, 0x200C, 0x200D, 0x2060,
                0xFEFF, 0x00AD};
        for (int c : hidden) {
            rejectedWithValidSibling("Which" + cp(c) + " step?");
        }
    }

    @Test void rc07c_directionMarksRejectWholeResult() throws Exception {
        for (int c : new int[] {0x200E, 0x200F, 0x061C}) {
            rejectedWithValidSibling("Which" + cp(c) + " step?");
        }
    }

    @Test void rc07c_controlsSeparatorsAndBidiControlsStillRejectWholeResult() throws Exception {
        for (int c : new int[] {0x0A, 0x0D, 0x09, 0x2028, 0x2029, 0x0085, 0x202E, 0x2066}) {
            rejectedWithValidSibling("Which" + cp(c) + " step?");
        }
    }

    @Test void rc07c_questionWithNoVisibleCharacterRejectsWholeResult() throws Exception {
        for (String invisible : new String[] {cp(0x200B), cp(0x3164) + cp(0xFFA0), cp(0x034F), cp(0xFE0F),
                cp(0x2060) + cp(0xFEFF)}) {
            rejectedWithValidSibling(invisible);
        }
    }

    @Test void rc07c_ordinaryQuestionsWithValidItemsAreKept() throws Exception {
        var result = ReportClarifyRules.validateOutput(out(
                List.of(u("button", "Which café page — the menu one?"),
                        u("stops", "Does the 👍 reaction fail too?")),
                List.of(m("Summary", "Does it also fail on example.com?"))), DRAFT);
        assertEquals(2, result.unclear().size());
        assertEquals(1, result.missing().size());
    }
}
