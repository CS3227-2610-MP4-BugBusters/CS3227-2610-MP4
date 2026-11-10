package mp4.discussion;

/**
 * Text rules shared by Reporter report fields and discussion comment bodies (report-discussion design D2).
 * Moved unchanged from {@code ReporterReportsHttpHandler.validateText}'s helpers.
 */
public final class TextRules {
    private TextRules() {}

    /** False for U+0000 or an unpaired surrogate, which PostgreSQL text cannot hold faithfully. */
    public static boolean isStorableText(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\0' || Character.isLowSurrogate(c)) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                i++;
            }
        }
        return true;
    }

    /** True when the value is empty or only Unicode whitespace. */
    public static boolean isBlank(String value) {
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (!isUnicodeWhitespace(codePoint)) return false;
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /** The Unicode White_Space property: Zs, Zl and Zp plus U+0009..U+000D and U+0085. */
    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isSpaceChar(codePoint) || (codePoint >= 0x09 && codePoint <= 0x0D) || codePoint == 0x85;
    }
}
