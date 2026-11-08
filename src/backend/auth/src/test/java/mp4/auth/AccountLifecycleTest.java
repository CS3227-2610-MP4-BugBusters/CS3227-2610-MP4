package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Real operator-process acceptance for AUTH-02 account recovery. */
class AccountLifecycleTest {
    static final String URL = required("MP4_AUTH_TEST_JDBC_URL");
    static final String USER = required("MP4_AUTH_TEST_DB_USER");
    static final String PASSWORD = required("MP4_AUTH_TEST_DB_PASSWORD");
    record Result(int exit, String output) {}
    record Snapshot(UUID id, String username, String role, String name, boolean active, long generation, String hash) {}
    record Fixture(String username, String password) {}

    @Test void disablePersistsInactiveGenerationAndPreservesIdentityAndHash() throws Exception {
        Fixture fixture = fixture(); Snapshot before = snapshot(fixture.username());
        for (int n = 1; n <= 2; n++) {
            Result result = invoke("", "disable", "--username", " " + fixture.username().toUpperCase(Locale.ROOT) + " ");
            success(result, "ACCOUNT_DISABLED", before);
            Snapshot after = snapshot(fixture.username());
            sameIdentity(before, after); assertFalse(after.active()); assertEquals(before.generation() + n, after.generation());
            assertTrue(before.hash().equals(after.hash()), "disable preserves password hash");
            secretSafe(result, fixture.password(), before.hash());
        }
    }
    @Test void resetReplacesHashAndGenerationWhilePreservingActiveAndInactiveState() throws Exception {
        for (boolean active : List.of(true, false)) {
            Fixture fixture = fixture();
            if (!active) try (var c = connection(); var s = c.prepareStatement("UPDATE app_accounts SET active=false WHERE username=?")) {
                s.setString(1, fixture.username()); assertEquals(1, s.executeUpdate());
            }
            Snapshot before = snapshot(fixture.username()); String replacement = UUID.randomUUID().toString();
            Result result = invoke(replacement, "reset-password", "--username", fixture.username(), "--password-stdin");
            success(result, "PASSWORD_RESET", before); Snapshot after = snapshot(fixture.username()); sameIdentity(before, after);
            assertEquals(active, after.active()); assertEquals(before.generation() + 1, after.generation());
            assertFalse(before.hash().equals(after.hash()), "reset replaces password hash");
            assertTrue(after.hash().matches("\\{bcrypt}\\$2[aby]\\$12\\$.*"), "reset uses BCrypt cost 12");
            var encoder = new BCryptPasswordEncoder(12);
            assertTrue(encoder.matches(replacement, after.hash().substring(8)), "new password must verify");
            assertFalse(encoder.matches(fixture.password(), after.hash().substring(8)), "old password must stop verifying");
            secretSafe(result, replacement, fixture.password(), before.hash(), after.hash());
        }
    }
    @Test void unknownAccountsAndInvalidLifecycleArgumentsDoNotMutateAccounts() throws Exception {
        Fixture fixture = fixture(); Snapshot before = snapshot(fixture.username()); long count = count();
        String missing = "missing-" + UUID.randomUUID();
        for (String operation : List.of("disable", "reset-password")) {
            Result result = operation.equals("disable") ? invoke("", operation, "--username", missing)
                : invoke(UUID.randomUUID().toString(), operation, "--username", missing, "--password-stdin");
            assertEquals(6, result.exit(), "missing account status"); assertEquals("ACCOUNT_NOT_FOUND", json(result).get("code"));
            assertTrue(before.equals(snapshot(fixture.username())), "missing account preserves existing row"); assertEquals(count, count());
        }
        for (String[] args : List.of(new String[]{"disable", "--username", fixture.username(), "--role", "MAINTAINER"},
            new String[]{"reset-password", "--username", fixture.username()},
            new String[]{"disable", "--username", fixture.username(), "--username", fixture.username()})) {
            Result result = invoke(UUID.randomUUID().toString(), args);
            assertEquals(2, result.exit()); assertEquals("VALIDATION_FAILED", json(result).get("code"));
            assertTrue(before.equals(snapshot(fixture.username())), "invalid command preserves complete row"); assertEquals(count, count());
        }
    }
    static Fixture fixture() throws Exception {
        Fixture fixture = new Fixture("life-" + UUID.randomUUID(), UUID.randomUUID().toString());
        Result result = invoke(fixture.password(), "create", "--username", fixture.username(), "--display-name", "Lifecycle fixture",
            "--role", "REPORTER", "--password-stdin");
        assertEquals(0, result.exit(), "existing create setup control"); assertEquals("ACCOUNT_CREATED", json(result).get("code"));
        return fixture;
    }
    static void sameIdentity(Snapshot before, Snapshot after) {
        assertEquals(before.id(), after.id()); assertEquals(before.username(), after.username());
        assertEquals(before.role(), after.role()); assertEquals(before.name(), after.name());
    }
    static void success(Result result, String code, Snapshot before) {
        assertEquals(0, result.exit(), "lifecycle command must succeed"); var data = json(result);
        assertEquals(Set.of("code", "id", "role"), data.keySet()); assertEquals(code, data.get("code"));
        assertEquals(before.id().toString(), data.get("id")); assertEquals(before.role(), data.get("role"));
    }
    static Map<String,Object> json(Result result) {
        try { return JsonParserFactory.getJsonParser().parseMap(result.output()); }
        catch (RuntimeException invalid) { throw new AssertionError("Command JSON could not be parsed"); }
    }
    static void secretSafe(Result result, String... secrets) {
        for (String secret : secrets) assertFalse(result.output().contains(secret), "command output must not disclose secrets");
    }
    static Connection connection() throws SQLException { return DriverManager.getConnection(URL, USER, PASSWORD); }
    static Snapshot snapshot(String username) throws Exception {
        try (var c = connection(); var q = c.prepareStatement("SELECT id,username,role,display_name,active,auth_generation,password_hash FROM app_accounts WHERE username=?")) {
            q.setString(1, username); try (var r = q.executeQuery()) {
                assertTrue(r.next(), "fixture row exists");
                Snapshot row = new Snapshot(r.getObject(1, UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getBoolean(5),r.getLong(6),r.getString(7));
                assertFalse(r.next()); return row;
            }
        }
    }
    static long count() throws Exception {
        try (var c = connection(); var q = c.createStatement(); var r = q.executeQuery("SELECT count(*) FROM app_accounts")) { assertTrue(r.next()); return r.getLong(1); }
    }
    static Result invoke(String input, String... args) throws Exception {
        String cp = System.getProperty("mp4.auth.command.classpath"); assertNotNull(cp, "runner supplies command classpath");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString());
        command.add("-cp"); command.add(cp); command.add("mp4.auth.AccountProvisioningCommand"); Collections.addAll(command, args);
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(Map.of("MP4_AUTH_JDBC_URL",URL,"MP4_AUTH_DB_USER",USER,"MP4_AUTH_DB_PASSWORD",PASSWORD));
        Process process = builder.start();
        var output = CompletableFuture.supplyAsync(() -> {
            try { byte[] bytes = process.getInputStream().readNBytes(8193);
                if (bytes.length > 8192) throw new AssertionError("Command output exceeded capture limit");
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException error) { throw new AssertionError("Command output capture failed"); }
        });
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "command deadline exceeded");
            String captured;
            try { captured = output.get(2, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException failed) { throw new AssertionError("Command output capture failed"); }
            assertFalse(captured.contains(PASSWORD), "database secret must not be printed");
            if (!input.isEmpty()) assertFalse(captured.contains(input), "password input must not be printed");
            return new Result(process.exitValue(), captured);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(2, TimeUnit.SECONDS), "command cleanup deadline exceeded"); }
            process.getInputStream().close(); process.getErrorStream().close();
        }
    }
    static String required(String name) {
        String value = System.getenv(name); assertNotNull(value, "disposable runner environment required"); return value;
    }
}
