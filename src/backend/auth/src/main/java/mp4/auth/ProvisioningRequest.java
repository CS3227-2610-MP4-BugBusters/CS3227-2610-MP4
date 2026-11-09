package mp4.auth;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict parser and value validation for the operator create command. */
record ProvisioningRequest(String username, String displayName, String role, UUID id) {
    private static final Set<String> ROLES = Set.of("REPORTER", "DEVELOPER", "MAINTAINER");

    static ProvisioningRequest parse(String[] args) {
        if (args == null || args.length < 1 || !args[0].equals("create")) throw new InvalidInput();
        Map<String, String> values = new HashMap<>();
        boolean passwordStdin = false;
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (flag.equals("--password-stdin")) {
                if (passwordStdin) throw new InvalidInput();
                passwordStdin = true;
            } else if (Set.of("--username", "--display-name", "--role", "--id").contains(flag)) {
                if (++i >= args.length || values.putIfAbsent(flag, args[i]) != null) throw new InvalidInput();
            } else throw new InvalidInput();
        }
        if (!passwordStdin || !values.keySet().containsAll(Set.of("--username", "--display-name", "--role"))) throw new InvalidInput();
        String username = normalizeUsername(values.get("--username"));
        String displayName = values.get("--display-name");
        if (displayName.isBlank() || displayName.length() > 200 || displayName.codePoints().anyMatch(Character::isISOControl)) throw new InvalidInput();
        String role = values.get("--role");
        if (!ROLES.contains(role)) throw new InvalidInput();
        UUID id;
        try {
            if (!values.containsKey("--id")) id = UUID.randomUUID();
            else {
                String requested = values.get("--id");
                if (!requested.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new InvalidInput();
                id = UUID.fromString(requested);
            }
        }
        catch (IllegalArgumentException e) { throw new InvalidInput(); }
        return new ProvisioningRequest(username, displayName, role, id);
    }

    static String normalizeUsername(String input) {
        try { return UsernamePolicy.normalize(input); }
        catch (IllegalArgumentException invalid) { throw new InvalidInput(); }
    }

    static void validatePassword(byte[] bytes) throws CharacterCodingException {
        if (bytes.length < 12 || bytes.length > 72) throw new InvalidInput();
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
    }

    static final class InvalidInput extends IllegalArgumentException { }
}
