package mp4.platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import mp4.auth.UsernamePolicy;
import jakarta.servlet.http.HttpServletRequest;

/** Strict, size-bounded parser for the login form-urlencoded contract. */
final class LoginFormParser {
    private LoginFormParser() { }

    static LoginForm parse(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(8193);
        if (body.length > 8192) throw new IllegalArgumentException("form too large");
        String raw = decodeUtf8(body);
        Map<String, String> fields = new HashMap<>();
        for (String pair : raw.split("&", -1)) {
            int separator = pair.indexOf('=');
            String name = decodeComponent(separator < 0 ? pair : pair.substring(0, separator));
            String value = decodeComponent(separator < 0 ? "" : pair.substring(separator + 1));
            if (name.codePoints().anyMatch(Character::isISOControl)
                    || !Set.of("username", "password").contains(name)
                    || fields.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("invalid form fields");
            }
        }
        if (!fields.keySet().equals(Set.of("username", "password"))) throw new IllegalArgumentException("missing form fields");
        String username = UsernamePolicy.normalize(fields.get("username"));
        String password = fields.get("password");
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < 1 || passwordBytes > 72) throw new IllegalArgumentException("invalid password length");
        return new LoginForm(username, password);
    }

    private static String decodeComponent(String component) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(component.length());
        for (int i = 0; i < component.length();) {
            char ch = component.charAt(i);
            if (ch == '%') {
                if (i + 2 >= component.length()) throw new IllegalArgumentException("invalid escape");
                int high = Character.digit(component.charAt(i + 1), 16);
                int low = Character.digit(component.charAt(i + 2), 16);
                if (high < 0 || low < 0 || component.charAt(i + 1) > 127 || component.charAt(i + 2) > 127) {
                    throw new IllegalArgumentException("invalid escape");
                }
                bytes.write((high << 4) | low);
                i += 3;
            } else if (ch == '+') {
                bytes.write(' ');
                i++;
            } else {
                int point = component.codePointAt(i);
                if (Character.isSurrogate(ch) && Character.charCount(point) == 1) throw new IllegalArgumentException("invalid unicode");
                bytes.writeBytes(new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(point);
            }
        }
        return decodeUtf8(bytes.toByteArray());
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("invalid UTF-8", invalid);
        }
    }

    record LoginForm(String username, String password) { }
}
