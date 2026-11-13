package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fix round 1 hardening of the RC-07b screens, the one-line question rule and blank quotes. */
class ReportClarifyScreeningHardeningTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ReportClarifyRules.Draft DRAFT =
            new ReportClarifyRules.Draft("Button", "### What happened?\nbutton  stops");

    private static String unclear(String quote, String question) throws Exception {
        return JSON.writeValueAsString(Map.of("unclear", List.of(Map.of("quote", quote, "question", question)),
                "missing", List.of()));
    }

    private static String missing(String question) throws Exception {
        return JSON.writeValueAsString(Map.of("unclear", List.of(),
                "missing", List.of(Map.of("category", "Summary", "question", question))));
    }

    private static void rejected(String question) throws Exception {
        String a = unclear("button", question), b = missing(question);
        assertThrows(IllegalArgumentException.class, () -> ReportClarifyRules.validateOutput(a, DRAFT), question);
        assertThrows(IllegalArgumentException.class, () -> ReportClarifyRules.validateOutput(b, DRAFT), question);
    }

    private static void accepted(String question) throws Exception {
        assertEquals(1, ReportClarifyRules.validateOutput(unclear("button", question), DRAFT).unclear().size(),
                question);
        assertEquals(1, ReportClarifyRules.validateOutput(missing(question), DRAFT).missing().size(), question);
    }

    // Item 2: credential terms with '_', '-', camelCase and format-character obfuscation.
    @Test void credentialTermsBehindUnderscoresHyphensAndCamelCaseAreRejected() throws Exception {
        for (String q : new String[] {"Paste your access_token", "What is the auth_token?", "Send the auth-token",
                "Share the accessToken value", "Paste the APIKey", "What is your api_key?", "Send your apikey",
                "What is the refresh_tokens list?", "Paste your OTPs"}) {
            rejected(q);
        }
    }

    @Test void extendedCredentialTermsAreRejected() throws Exception {
        for (String q : new String[] {"Share your credentials", "Which credential did you use?",
                "What is your pass word?", "Send your passwd", "What is your PIN?", "Send the OTP",
                "Enter your 2FA code", "What is the 2-FA code?", "Share the one-time code",
                "Send the one time code", "What was the verification code?", "Paste the login code",
                "Paste the session cookie", "Send the cookies"}) {
            rejected(q);
        }
    }

    @Test void normalizedFormsOfCredentialTermsAreRejected() throws Exception {
        for (String q : new String[] {"Share your pass​word", "Send the ＴＯＫＥＮ",
                "Share the s­ecret", "PassWord please"}) {
            rejected(q);
        }
    }

    @Test void ordinaryWordsContainingTermsStillPass() throws Exception {
        for (String q : new String[] {"Which tokenizer rule applies?", "Is the secretary page affected?",
                "Does the passwordless flow fail?", "Can you pinpoint the step?", "Does the spinner keep spinning?",
                "Which option did you pick?", "Is the cookbook page affected?", "Did it happen on the login page?",
                "Which error code did you see?", "Was the tokenizer_config page open?",
                "Which credentialing step failed?", "Did it fail on an iPhone?"}) {
            accepted(q);
        }
    }

    // Item 3: URL and email screens after normalization.
    @Test void fullwidthAndDefangedUrlsAndEmailsAreRejected() throws Exception {
        for (String q : new String[] {"Open ｈｔｔｐｓ://evil.example",
                "Open ｈｔｔｐ://evil.example", "Visit ｗｗｗ.evil.example",
                "Open hxxp://evil.example", "Open HXXPS://evil.example", "Open ht​tps://evil.example",
                "Mail a.b＠example.com", "Mail a_b@evil-site.example"}) {
            rejected(q);
        }
    }

    @Test void bareDomainsAreNotTreatedAsUrls() throws Exception {
        accepted("Does it also fail on example.com?");
    }

    // Item 4: one-line rule.
    @Test void lineSeparatorsControlsAndBidiControlsAreRejected() throws Exception {
        int[] forbidden = {0x2028, 0x2029, 0x0085, 0x0009, 0x0000 + 0x1B, 0x007F, 0x0080, 0x009F,
                0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069};
        for (int cp : forbidden) {
            rejected("Which" + Character.toString(cp) + " step?");
        }
    }

    @Test void ordinaryUnicodeQuestionsStillPass() throws Exception {
        accepted("Which café page — the “menu” one?");
        accepted("Does the 👍 reaction fail too?");
    }

    // Item 5: blank quotes.
    @Test void blankOrWhitespaceOnlyQuoteIsRejected() throws Exception {
        String twoSpaces = unclear("  ", "Which part?");
        String newline = unclear("\n", "Which part?");
        assertThrows(IllegalArgumentException.class, () -> ReportClarifyRules.validateOutput(twoSpaces, DRAFT));
        assertThrows(IllegalArgumentException.class, () -> ReportClarifyRules.validateOutput(newline, DRAFT));
        assertEquals(1, ReportClarifyRules.validateOutput(unclear("button  stops", "Stops how?"), DRAFT)
                .unclear().size());
    }
}
