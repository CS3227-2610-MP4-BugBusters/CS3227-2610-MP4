package mp4.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProvisioningValidationTest {
    @Test
    void usernameNormalizationIsAsciiOnly() {
        assertEquals("reporter.one", ProvisioningRequest.normalizeUsername(" Reporter.One "));
        assertThrows(ProvisioningRequest.InvalidInput.class, () -> ProvisioningRequest.normalizeUsername("Kelvin"));
    }

    @Test
    void explicitUuidMustUseCanonicalForm() {
        assertThrows(ProvisioningRequest.InvalidInput.class, () -> request("--id", "1-1-1-1-1"));
        assertEquals(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                request("--id", "11111111-1111-1111-1111-111111111111").id());
    }

    @Test
    void jdbcUrlRequiresPlainPostgresAuthorityAndDatabasePath() {
        assertDoesNotThrow(() -> ProvisioningDatabase.Config.fromEnvironment(env("jdbc:postgresql://[::1]:5432/mp4")));
        for (String invalid : new String[]{"jdbc:postgresql://user@localhost:5432/mp4", "jdbc:postgresql://localhost:5432/mp4?ssl=false",
                "jdbc:postgresql://localhost:5432/mp4#fragment", "jdbc:postgresql://localhost:5432/mp4 extra", "jdbc:postgresql://localhost/mp4"}) {
            assertThrows(IllegalArgumentException.class, () -> ProvisioningDatabase.Config.fromEnvironment(env(invalid)), invalid);
        }
    }

    @Test
    void invalidCommandIsRejectedBeforeDatabaseBootstrap() throws Exception {
        String cp = System.getProperty("mp4.auth.command.classpath");
        assertNotNull(cp);
        String javaExe = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(javaExe,
                "-cp", cp, "mp4.auth.AccountProvisioningCommand", "create", "--username", "valid.user", "--display-name", "Valid",
                "--role", "OWNER", "--password-stdin");
        builder.environment().put("MP4_AUTH_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/unreachable");
        builder.environment().put("MP4_AUTH_DB_USER", "operator");
        builder.environment().put("MP4_AUTH_DB_PASSWORD", "protected");
        var process = builder.start();
        try (var stdin = process.getOutputStream()) { stdin.write("a sufficiently long password".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        boolean completed = false;
        try {
            completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(completed, "invalid command exceeded its deadline");
            assertEquals(2, process.exitValue());
            String stdout = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(("{\"code\":\"VALIDATION_FAILED\"}" + System.lineSeparator()).equals(stdout), "command emits only the expected safe JSON line");
            assertTrue(stderr.isEmpty());
        } finally {
            if (!completed && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    private static ProvisioningRequest request(String... id) {
        var args = new java.util.ArrayList<>(java.util.List.of("create", "--username", "valid.user", "--display-name", "Valid",
                "--role", "REPORTER", "--password-stdin"));
        if (id.length > 0) { args.add("--id"); args.add(id[1]); }
        return ProvisioningRequest.parse(args.toArray(String[]::new));
    }

    private static Map<String, String> env(String url) {
        return Map.of("MP4_AUTH_JDBC_URL", url, "MP4_AUTH_DB_USER", "operator", "MP4_AUTH_DB_PASSWORD", "protected");
    }
}
