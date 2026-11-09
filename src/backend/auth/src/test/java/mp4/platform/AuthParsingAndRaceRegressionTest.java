package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Focused tests for strict byte decoding and credential reset races. */
class AuthParsingAndRaceRegressionTest {
    @Test void formParserRejectsMalformedPercentUtf8AndUnicodeUsernameAliases() throws Exception {
        var malformed = form("username=%C3%28&password=valid-password");
        assertThrows(IllegalArgumentException.class, () -> LoginFormParser.parse(malformed));
        var alias = form("username=%E2%84%AAelvin&password=valid-password");
        assertThrows(IllegalArgumentException.class, () -> LoginFormParser.parse(alias));
        var valid = form("username=+Alice-1+&password=valid-password");
        assertEquals("alice-1", LoginFormParser.parse(valid).username());
    }

    @Test void concurrentPasswordResetCannotAuthenticateTheOldSnapshot() throws Exception {
        initializeAuthSchema();
        String url = required("MP4_AUTH_TEST_JDBC_URL");
        String dbUser = required("MP4_AUTH_TEST_DB_USER");
        String dbPassword = required("MP4_AUTH_TEST_DB_PASSWORD");
        UUID id = UUID.randomUUID();
        String username = "race-" + id;
        String oldHash = "{bcrypt}" + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
                .encode("old-password");
        String newHash = "{bcrypt}" + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
                .encode("new-password");
        try (Connection connection = DriverManager.getConnection(url, dbUser, dbPassword);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO public.app_accounts(id,username,display_name,password_hash,role,active,auth_generation)
                     VALUES (?,?,?,?,'REPORTER',TRUE,0)
                     """)) {
            insert.setObject(1, id); insert.setString(2, username); insert.setString(3, "Race fixture");
            insert.setString(4, oldHash); insert.executeUpdate();
        }
        PasswordEncoder resettingEncoder = new PasswordEncoder() {
            @Override public String encode(CharSequence raw) { return oldHash; }
            @Override public boolean matches(CharSequence raw, String encoded) {
                assertEquals(oldHash, encoded);
                try (Connection connection = DriverManager.getConnection(url, dbUser, dbPassword);
                     PreparedStatement update = connection.prepareStatement("""
                             UPDATE public.app_accounts SET password_hash=?, auth_generation=auth_generation+1 WHERE id=?
                             """)) {
                    update.setString(1, newHash); update.setObject(2, id); assertEquals(1, update.executeUpdate());
                    return true;
                } catch (Exception failure) { throw new AssertionError("reset fixture could not commit"); }
            }
        };
        var provider = new AuthProvider(new AuthAccountRepository(new DriverManagerDataSource(url, dbUser, dbPassword)), resettingEncoder);
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(
                new UsernamePasswordAuthenticationToken(username, "old-password")));
        try (Connection connection = DriverManager.getConnection(url, dbUser, dbPassword);
             PreparedStatement query = connection.prepareStatement("SELECT password_hash,auth_generation FROM public.app_accounts WHERE id=?")) {
            query.setObject(1, id);
            try (ResultSet row = query.executeQuery()) { assertTrue(row.next()); assertEquals(newHash, row.getString(1)); assertEquals(1, row.getLong(2)); }
        }
    }

    @Test void userBudgetIsAtomicAcrossConcurrentAdmissions() throws Exception {
        initializeAuthSchema();
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        var storeA = limiter(secret);
        var storeB = limiter(secret);
        String user = "parallel-" + UUID.randomUUID();
        String client = "parallel-client-" + UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(12);
        try {
            var jobs = new ArrayList<java.util.concurrent.Callable<Integer>>();
            for (int i = 0; i < 30; i++) {
                var store = i % 2 == 0 ? storeA : storeB;
                jobs.add(() -> store.admit(user, client));
            }
            int admitted = 0;
            for (var result : executor.invokeAll(jobs)) if (result.get() == 0) admitted++;
            assertEquals(10, admitted, "concurrent admissions cannot exceed the user budget");
        } finally { executor.shutdownNow(); }
    }

    @Test void clientBudgetStopsAttemptOneHundredAndOne() throws Exception {
        initializeAuthSchema();
        var store = limiter();
        String client = "client-cap-" + UUID.randomUUID();
        for (int i = 0; i < 100; i++) assertEquals(0, store.admit("client-user-" + UUID.randomUUID(), client));
        assertTrue(store.admit("client-user-" + UUID.randomUUID(), client) > 0);
    }

    @Test void expiredAttemptCleanupCommitsOnAdmittedAndRateLimitedPaths() throws Exception {
        initializeAuthSchema();
        var store = limiter();
        String staleDigest = "f".repeat(64);
        insertExpired(staleDigest);
        assertEquals(0, store.admit("clean-" + UUID.randomUUID(), "clean-client-" + UUID.randomUUID()));
        assertEquals(0, countDigest(staleDigest), "cleanup commits on an admitted request");

        String user = "denied-" + UUID.randomUUID();
        String client = "denied-client-" + UUID.randomUUID();
        for (int i = 0; i < 10; i++) assertEquals(0, store.admit(user, client));
        insertExpired(staleDigest);
        assertTrue(store.admit(user, client) > 0);
        assertEquals(0, countDigest(staleDigest), "cleanup commits even when admission is denied");
    }

    private static LoginAttemptStore limiter() throws Exception {
        return limiter(UUID.randomUUID().toString() + UUID.randomUUID());
    }

    private static LoginAttemptStore limiter(String secret) throws Exception {
        return new LoginAttemptStore(new DriverManagerDataSource(required("MP4_AUTH_TEST_JDBC_URL"),
                required("MP4_AUTH_TEST_DB_USER"), required("MP4_AUTH_TEST_DB_PASSWORD")), Clock.systemUTC(),
                secret);
    }

    private static void initializeAuthSchema() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(required("MP4_AUTH_TEST_JDBC_URL"), required("MP4_AUTH_TEST_DB_USER"), required("MP4_AUTH_TEST_DB_PASSWORD"))
                .defaultSchema("public").locations("classpath:db/auth").table("mp4_auth_schema_history")
                .baselineOnMigrate(true).baselineVersion("99").load().migrate();
    }

    private static void insertExpired(String digest) throws Exception {
        try (Connection connection = DriverManager.getConnection(required("MP4_AUTH_TEST_JDBC_URL"),
                required("MP4_AUTH_TEST_DB_USER"), required("MP4_AUTH_TEST_DB_PASSWORD"));
             PreparedStatement insert = connection.prepareStatement("INSERT INTO public.mp4_auth_login_attempt(key_digest,attempted_at) VALUES (?,?)")) {
            insert.setString(1, digest); insert.setTimestamp(2, Timestamp.from(Instant.now().minus(Duration.ofHours(25)))); insert.executeUpdate();
        }
    }

    private static long countDigest(String digest) throws Exception {
        try (Connection connection = DriverManager.getConnection(required("MP4_AUTH_TEST_JDBC_URL"),
                required("MP4_AUTH_TEST_DB_USER"), required("MP4_AUTH_TEST_DB_PASSWORD"));
             PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM public.mp4_auth_login_attempt WHERE key_digest=?")) {
            query.setString(1, digest);
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private static MockHttpServletRequest form(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException("Disposable runner is required");
        return value;
    }

    private record DriverManagerDataSource(String url, String username, String password)
            implements javax.sql.DataSource {
        @Override public Connection getConnection() throws java.sql.SQLException { return DriverManager.getConnection(url, username, password); }
        @Override public Connection getConnection(String user, String pass) throws java.sql.SQLException { return DriverManager.getConnection(url, user, pass); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { throw new java.sql.SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
