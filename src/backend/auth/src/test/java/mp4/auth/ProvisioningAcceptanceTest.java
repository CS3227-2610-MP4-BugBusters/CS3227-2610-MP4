package mp4.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL acceptance tests for AUTH-01 provisioning and the AUTH-02 hash boundary. */
class ProvisioningAcceptanceTest {
    private static final String PASSWORD = "provisioning-" + UUID.randomUUID() + "-2026";
    private static final String URL = required("MP4_AUTH_TEST_JDBC_URL");
    private static final String DB_USER = required("MP4_AUTH_TEST_DB_USER");
    private static final String DB_PASSWORD = required("MP4_AUTH_TEST_DB_PASSWORD");

    @BeforeEach
    void removeOnlyAuthFixtureRows() throws Exception {
        try (var c = DriverManager.getConnection(URL, DB_USER, DB_PASSWORD);
             var s = c.createStatement()) {
            if (tableExists(c)) s.executeUpdate("DELETE FROM app_accounts WHERE username LIKE 'red-%'");
        }
    }

    @Test
    void provisionsEachRoleWithGeneratedUuidAndAdaptiveHash() throws Exception {
        for (String role : new String[]{"REPORTER", "DEVELOPER", "MAINTAINER"}) {
            String username = "red-" + role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
            UUID requestedId = role.equals("REPORTER") ? null : UUID.randomUUID();
            Result result = requestedId == null
                    ? invoke(PASSWORD, "create", "--username", username, "--display-name", "Red User", "--role", role, "--password-stdin")
                    : invoke(PASSWORD, "create", "--username", username, "--display-name", "Red User", "--role", role, "--password-stdin", "--id", requestedId.toString());
            assertEquals(0, result.exitCode, "provision command must succeed");
            assertEquals("ACCOUNT_CREATED", jsonValue(result.stdout, "code"));
            UUID id = UUID.fromString(jsonValue(result.stdout, "id"));
            assertEquals(role, jsonValue(result.stdout, "role"));
            try (var c = DriverManager.getConnection(URL, DB_USER, DB_PASSWORD);
                 var q = c.prepareStatement("SELECT id, role, active, auth_generation, password_hash FROM app_accounts WHERE username = ?")) {
                q.setString(1, username);
                try (var rs = q.executeQuery()) {
                    assertTrue(rs.next(), "successful command must persist before returning");
                    assertEquals(id, rs.getObject("id", UUID.class));
                    if (requestedId != null) assertEquals(requestedId, rs.getObject("id", UUID.class));
                    assertEquals(role, rs.getString("role"));
                    assertTrue(rs.getBoolean("active"));
                    assertEquals(0, rs.getLong("auth_generation"));
                    String hash = rs.getString("password_hash");
                    assertFalse(PASSWORD.equals(hash));
                    assertTrue(hash.matches("\\{bcrypt}\\$2[aby]\\$12\\$.*"), "new passwords use BCrypt cost 12");
                    assertTrue(new org.springframework.security.crypto.password.DelegatingPasswordEncoder("bcrypt",
                            java.util.Map.of("bcrypt", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)))
                            .matches(PASSWORD, hash));
                    assertFalse(hash.contains(PASSWORD));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void duplicateNormalizedUsernameRefusesWithoutChangingExistingRecord() throws Exception {
        String username = "red-duplicate-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = UUID.randomUUID();
        Result first = invoke(PASSWORD, "create", "--username", username, "--display-name", "Original",
                "--role", "REPORTER", "--password-stdin", "--id", id.toString());
        assertEquals(0, first.exitCode);
        Snapshot before = snapshot(username);
        Result duplicate = invoke("replacement-password-" + UUID.randomUUID(), "create", "--username", " " + username.toUpperCase() + " ",
                "--display-name", "Replacement", "--role", "MAINTAINER", "--password-stdin", "--id", UUID.randomUUID().toString());
        assertEquals(3, duplicate.exitCode, "duplicate normalized username must be refused");
        assertEquals("ACCOUNT_EXISTS", jsonValue(duplicate.stdout, "code"));
        assertTrue(before.equals(snapshot(username)), "duplicate must preserve the complete original account row");
    }

    @Test
    void duplicateExplicitUuidRefusesAndDoesNotCreateSecondAccount() throws Exception {
        UUID id = UUID.randomUUID();
        Result first = invoke(PASSWORD, "create", "--username", "red-id-a-" + id.toString().substring(0, 8),
                "--display-name", "First", "--role", "DEVELOPER", "--password-stdin", "--id", id.toString());
        assertEquals(0, first.exitCode);
        Result second = invoke(PASSWORD, "create", "--username", "red-id-b-" + id.toString().substring(0, 8),
                "--display-name", "Second", "--role", "REPORTER", "--password-stdin", "--id", id.toString());
        assertEquals(3, second.exitCode);
        assertEquals("ACCOUNT_EXISTS", jsonValue(second.stdout, "code"));
        try (var c = DriverManager.getConnection(URL, DB_USER, DB_PASSWORD);
             var q = c.prepareStatement("SELECT count(*) FROM app_accounts WHERE id = ?")) {
            q.setObject(1, id);
            try (var rs = q.executeQuery()) { rs.next(); assertEquals(1, rs.getInt(1)); }
        }
    }

    @Test
    void rejectsInvalidRoleAndPasswordAsArgumentWithoutWriting() throws Exception {
        String username = "red-invalid-" + UUID.randomUUID().toString().substring(0, 8);
        Result role = invoke(PASSWORD, "create", "--username", username, "--display-name", "Nope", "--role", "OWNER",
                "--password-stdin");
        assertEquals(2, role.exitCode);
        assertEquals("VALIDATION_FAILED", jsonValue(role.stdout, "code"));
        Result argSecret = invoke("unused-stdin", "create", "--username", username, "--display-name", "Nope", "--role", "REPORTER",
                "--password", PASSWORD);
        assertEquals(2, argSecret.exitCode);
        assertEquals("VALIDATION_FAILED", jsonValue(argSecret.stdout, "code"));
        if (tableExists()) {
            try (var c = DriverManager.getConnection(URL, DB_USER, DB_PASSWORD);
                 var q = c.prepareStatement("SELECT count(*) FROM app_accounts WHERE username = ?")) {
                q.setString(1, username);
                try (var rs = q.executeQuery()) { rs.next(); assertEquals(0, rs.getInt(1)); }
            }
        }
    }

    @Test
    void acceptsUtf16DisplayNameLimitAnd72PasswordBytesButRejectsExcess() throws Exception {
        String uname = "red-boundary-" + UUID.randomUUID().toString().substring(0, 8);
        String name200 = "😀".repeat(100);
        String pw72 = "é".repeat(36);
        Result accepted = invoke(pw72, "create", "--username", uname, "--display-name", name200,
                "--role", "REPORTER", "--password-stdin");
        assertEquals(0, accepted.exitCode);
        String uname2 = "red-too-long-" + UUID.randomUUID().toString().substring(0, 8);
        Result tooLong = invoke(pw72, "create", "--username", uname2, "--display-name", "😀".repeat(101),
                "--role", "REPORTER", "--password-stdin");
        assertEquals(2, tooLong.exitCode);
        assertEquals("VALIDATION_FAILED", jsonValue(tooLong.stdout, "code"));
        Result pw73 = invoke("é".repeat(37), "create", "--username", uname2, "--display-name", "Boundary",
                "--role", "REPORTER", "--password-stdin");
        assertEquals(2, pw73.exitCode);
        assertEquals("VALIDATION_FAILED", jsonValue(pw73.stdout, "code"));
    }

    private static Result invoke(String secret, String operation, String... commandArgs) throws Exception {
        String cp = System.getProperty("mp4.auth.command.classpath");
        assertNotNull(cp, "test runner must provide the command runtime classpath");
        var args = new java.util.ArrayList<String>();
        args.add(operation);
        java.util.Collections.addAll(args, commandArgs);
        var command = new java.util.ArrayList<String>();
        command.add(javaExecutable()); command.add("-cp"); command.add(cp);
        command.add("mp4.auth.AccountProvisioningCommand"); command.addAll(args);
        var builder = new ProcessBuilder(command).redirectErrorStream(false);
        builder.environment().put("MP4_AUTH_JDBC_URL", URL);
        builder.environment().put("MP4_AUTH_DB_USER", DB_USER);
        builder.environment().put("MP4_AUTH_DB_PASSWORD", DB_PASSWORD);
        Process process = builder.start();
        try (var stdin = process.getOutputStream()) { stdin.write(secret.getBytes(StandardCharsets.UTF_8)); }
        var stdout = java.util.concurrent.CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream()));
        var stderr = java.util.concurrent.CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream()));
        boolean completed = false;
        try {
            completed = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(completed, "command exceeded 15 second deadline");
            String out = stdout.get(2, java.util.concurrent.TimeUnit.SECONDS);
            String err = stderr.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(out.contains(secret), "standard output must not disclose password input");
            assertFalse(err.contains(secret), "standard error must not disclose password input");
            return new Result(process.exitValue(), out, err);
        } finally {
            if (!completed && process.isAlive()) { process.destroyForcibly(); process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); }
        }
    }

    private static String readBounded(java.io.InputStream stream) {
        try { return new String(stream.readNBytes(4096), StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { return ""; }
    }

    private static String javaExecutable() {
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    private static String jsonValue(String json, String key) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json);
        assertTrue(matcher.find(), "expected JSON string field " + key);
        return matcher.group(1);
    }

    private static boolean tableExists() throws Exception {
        try (var c = DriverManager.getConnection(URL, DB_USER, DB_PASSWORD)) { return tableExists(c); }
    }

    private static boolean tableExists(java.sql.Connection c) throws Exception {
        try (var q = c.prepareStatement("SELECT to_regclass('public.app_accounts') IS NOT NULL"); var rs = q.executeQuery()) {
            rs.next(); return rs.getBoolean(1);
        }
    }

    private static Snapshot snapshot(String username) throws Exception {
        try (var c = DriverManager.getConnection(URL, DB_USER, DB_PASSWORD);
             var q = c.prepareStatement("SELECT id, role, display_name, active, auth_generation, password_hash FROM app_accounts WHERE username = ?")) {
            q.setString(1, username);
            try (var rs = q.executeQuery()) {
                assertTrue(rs.next(), "expected one provisioned fixture row");
                Snapshot row = new Snapshot(rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("display_name"),
                        rs.getBoolean("active"), rs.getLong("auth_generation"), rs.getString("password_hash"));
                assertFalse(rs.next(), "username must identify one row");
                return row;
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " is required; run the disposable PostgreSQL runner");
        assertFalse(value.isBlank(), name + " must not be blank");
        return value;
    }

    private record Result(int exitCode, String stdout, String stderr) {}
    private record Snapshot(UUID id, String role, String displayName, boolean active, long generation, String hash) {}
}
