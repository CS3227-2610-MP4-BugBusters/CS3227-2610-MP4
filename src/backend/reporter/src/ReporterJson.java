package mp4.reporter;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal strict JSON (RFC 8259) reading and writing for the Reporter API, JDK only.
 *
 * <p>Reading accepts only what a create body may legally be: a UTF-8 object whose values are all
 * strings. Anything else (other value types, duplicate keys, trailing data, malformed UTF-8 or
 * JSON) is rejected with {@link IllegalArgumentException}, whose message is never sent to clients.
 * {@code \\u} escapes are decoded verbatim, so an escaped unpaired surrogate stays in the result
 * for the caller to reject instead of being replaced.
 */
final class ReporterJson {
    private ReporterJson() {}

    static Map<String, String> parseStringObject(byte[] body) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Body is not valid UTF-8", malformed);
        }
        var parser = new Parser(text);
        Map<String, String> object = parser.stringObject();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Trailing data after JSON value");
        }
        return object;
    }

    static String quote(String value) {
        var result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
                pos++;
            }
        }

        Map<String, String> stringObject() {
            skipWhitespace();
            expect('{');
            var result = new LinkedHashMap<String, String>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                String value = string();
                if (result.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("Duplicate key");
                }
                skipWhitespace();
                char next = next();
                if (next == '}') return result;
                if (next != ',') throw new IllegalArgumentException("Expected ',' or '}'");
            }
        }

        private String string() {
            expect('"');
            var result = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return result.toString();
                if (c < 0x20) throw new IllegalArgumentException("Unescaped control character");
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        int unit = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = hexDigit(next());
                            if (digit < 0) throw new IllegalArgumentException("Invalid \\u escape");
                            unit = (unit << 4) | digit;
                        }
                        result.append((char) unit);
                    }
                    default -> throw new IllegalArgumentException("Invalid escape");
                }
            }
        }

        // ASCII only: Character.digit would also accept non-ASCII digits such as U+FF11.
        private static int hexDigit(char value) {
            if (value >= '0' && value <= '9') return value - '0';
            if (value >= 'a' && value <= 'f') return value - 'a' + 10;
            if (value >= 'A' && value <= 'F') return value - 'A' + 10;
            return -1;
        }

        private char peek() {
            if (atEnd()) throw new IllegalArgumentException("Unexpected end of input");
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char expected) {
            if (next() != expected) throw new IllegalArgumentException("Unexpected character");
        }
    }
}
