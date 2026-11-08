package mp4.auth;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Operator-only entry point. It deliberately emits one safe JSON result line. */
public final class AccountProvisioningCommand {
    private AccountProvisioningCommand() {}

    public static void main(String[] args) {
        int status;
        try {
            ProvisioningRequest request = ProvisioningRequest.parse(args);
            byte[] password = readPassword();
            ProvisioningRequest.validatePassword(password);
            ProvisioningDatabase.Config config = ProvisioningDatabase.Config.fromEnvironment(System.getenv());
            ProvisioningDatabase.initialize(config);
            AccountProvisioningService.Created created = new AccountProvisioningService(config).create(request, password);
            output(Map.of("code", "ACCOUNT_CREATED", "id", created.id().toString(), "role", created.role()));
            status = 0;
        } catch (CharacterCodingException | IllegalArgumentException e) {
            output(Map.of("code", "VALIDATION_FAILED"));
            status = 2;
        } catch (AccountProvisioningService.DuplicateAccount e) {
            output(Map.of("code", "ACCOUNT_EXISTS"));
            status = 3;
        } catch (Exception e) {
            output(Map.of("code", "STORAGE_UNAVAILABLE"));
            status = 4;
        }
        if (status != 0) System.exit(status);
    }

    private static byte[] readPassword() throws IOException {
        byte[] bytes = System.in.readNBytes(73);
        if (bytes.length > 72) throw new IllegalArgumentException("invalid password length");
        return bytes;
    }

    private static void output(Map<String, String> fields) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var field : fields.entrySet()) {
            if (!first) json.append(',');
            json.append('"').append(field.getKey()).append("\":\"").append(field.getValue()).append('"');
            first = false;
        }
        System.out.println(json.append('}'));
    }
}
