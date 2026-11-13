package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * RC-07c "no visible character": final security review finding 1 (2026-11-13). Questions made only of
 * blank-rendering characters, or containing one inside a word, reject the whole result.
 */
class ReportClarifyVisibleCharacterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ReportClarifyRules.Draft DRAFT =
            new ReportClarifyRules.Draft("Button", "### What happened?\nbutton  stops");

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static String out(String question) throws Exception {
        return JSON.writeValueAsString(Map.of(
                "unclear", List.of(Map.of("quote", "button", "question", "Which button?")),
                "missing", List.of(Map.of("category", "Summary", "question", question))));
    }

    private static void rejected(String question) {
        assertThrows(IllegalArgumentException.class, () -> ReportClarifyRules.validateOutput(out(question), DRAFT),
                () -> "should reject " + question.codePoints().mapToObj(c -> String.format("U+%04X", c)).toList());
    }

    private static void accepted(String question) throws Exception {
        var result = ReportClarifyRules.validateOutput(out(question), DRAFT);
        assertEquals(question, result.missing().get(0).question(), "accepted text is returned unchanged");
    }

    @Test void onlyBrailleBlanksAreRejected() {
        rejected(cp(0x2800));
        rejected(cp(0x2800) + " " + cp(0x2800));
    }

    @Test void onlyCombiningMarksAreRejected() {
        rejected(cp(0x0301) + cp(0x0302) + cp(0x0303));
        rejected(" " + cp(0x20DD) + " ");
    }

    @Test void privateUseAndUnassignedCodePointsAreRejected() {
        rejected(cp(0xE000));
        rejected("Which " + cp(0xE000) + " browser?");
        rejected(cp(0x0378));
        rejected("Which " + cp(0x0378) + " browser?");
    }

    @Test void objectReplacementAndNullNoteheadAreRejected() {
        rejected(cp(0xFFFC));
        rejected(cp(0x1D159));
        rejected("Which " + cp(0xFFFC) + " page?");
    }

    @Test void onlyPunctuationOrSymbolsAreRejected() {
        rejected("?");
        rejected("... ?!");
    }

    @Test void brailleBlankCannotSplitAScreenedWord() {
        rejected("What is your pass" + cp(0x2800) + "word?");
    }

    @Test void ordinaryQuestionsStillPass() throws Exception {
        accepted("Which browser and version?");
        accepted("Which file was 40 MB?");
        accepted("在哪个页面出现错误？");
        accepted("Did the page show café menus?");
        accepted("Does it fail after 2 tries? 🙂");
        accepted("Which step: 1, 2 or 3?");
    }
}
