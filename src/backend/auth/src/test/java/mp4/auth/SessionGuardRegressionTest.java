package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** New repair-round coverage for session-store failures and auth request shape. */
class SessionGuardRegressionTest {
    private static SessionAuthenticationTest.Server server;

    @BeforeAll static void start() throws Exception {
        SessionAuthenticationTest.db = ProvisioningDatabase.Config.fromEnvironment(Map.of(
                "MP4_AUTH_JDBC_URL", Objects.requireNonNull(System.getenv("MP4_AUTH_TEST_JDBC_URL")),
                "MP4_AUTH_DB_USER", Objects.requireNonNull(System.getenv("MP4_AUTH_TEST_DB_USER")),
                "MP4_AUTH_DB_PASSWORD", Objects.requireNonNull(System.getenv("MP4_AUTH_TEST_DB_PASSWORD"))));
        ProvisioningDatabase.initialize(SessionAuthenticationTest.db);
        SessionAuthenticationTest.resetLoginAttemptFixtures();
        server = new SessionAuthenticationTest.Server();
    }

    @AfterAll static void stop() { if (server != null) server.close(); }

    @Test void sessionReadOutageReturnsSafe503Envelope() throws Exception {
        var account = SessionAuthenticationTest.account("REPORTER");
        var browser = login(account);
        try {
            execute("ALTER TABLE public.mp4_auth_session RENAME TO mp4_auth_session_offline");
            var response = browser.get("/api/auth/me");
            SessionAuthenticationTest.error(response, 503, "STORAGE_UNAVAILABLE");
            assertFalse(response.body().contains("mp4_auth_session"));
            assertFalse(response.body().contains("SQLException"));
        } finally {
            execute("ALTER TABLE public.mp4_auth_session_offline RENAME TO mp4_auth_session");
        }
    }

    @Test void sessionSaveOutageCannotReturnSuccessfulIdentityBody() throws Exception {
        var account = SessionAuthenticationTest.account("REPORTER");
        var browser = login(account);
        execute("""
                CREATE FUNCTION public.mp4_auth_test_reject_session_save() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test storage outage'; END $$
                """);
        execute("""
                CREATE TRIGGER mp4_auth_test_reject_session_save
                BEFORE INSERT OR UPDATE ON public.mp4_auth_session_attributes
                FOR EACH ROW EXECUTE FUNCTION public.mp4_auth_test_reject_session_save()
                """);
        try {
            var response = browser.get("/api/auth/me");
            SessionAuthenticationTest.error(response, 503, "STORAGE_UNAVAILABLE");
            assertFalse(response.body().contains(account.id().toString()));
        } finally {
            execute("DROP TRIGGER IF EXISTS mp4_auth_test_reject_session_save ON public.mp4_auth_session_attributes");
            execute("DROP FUNCTION IF EXISTS public.mp4_auth_test_reject_session_save()");
        }
    }

    @Test void csrfMeAndLogoutRejectQueriesOrBodiesWithoutInvalidatingSession() throws Exception {
        var anonymous = new SessionAuthenticationTest.Browser(server);
        SessionAuthenticationTest.status(400, anonymous.request("GET", "/api/auth/csrf?extra=1", ""), "CSRF query selector");
        SessionAuthenticationTest.status(400, anonymous.request("GET", "/api/auth/csrf", "unexpected"), "CSRF body");
        SessionAuthenticationTest.status(401, anonymous.request("GET", "/api/auth/me?user=other", ""), "anonymous me query selector");

        var account = SessionAuthenticationTest.account("REPORTER");
        var browser = login(account);
        SessionAuthenticationTest.status(400, browser.get("/api/auth/me?user=other"), "authenticated me query selector");
        String token = browser.csrf();
        SessionAuthenticationTest.status(400, browser.request("POST", "/api/auth/logout?next=1", "",
                "X-CSRF-TOKEN", token), "logout query selector");
        SessionAuthenticationTest.status(400, browser.request("POST", "/api/auth/logout", "x",
                "X-CSRF-TOKEN", token), "logout body");
        SessionAuthenticationTest.identity(browser.get("/api/auth/me"), account);
    }

    @Test void currentAccountRoleIsRefreshedBeforeProtectedPathAuthorization() throws Exception {
        var account = SessionAuthenticationTest.account("REPORTER");
        var browser = login(account);
        try (var connection = DriverManager.getConnection(SessionAuthenticationTest.db.url(),
                SessionAuthenticationTest.db.user(), SessionAuthenticationTest.db.password());
             var update = connection.prepareStatement("UPDATE public.app_accounts SET role='DEVELOPER' WHERE id=?")) {
            update.setObject(1, account.id());
            assertEquals(1, update.executeUpdate());
        }
        SessionAuthenticationTest.status(200, browser.get("/api/developer/reports"), "fresh role reaches existing Developer handler");
    }

    @Test void inactiveDenialPrecedesGenerationRevocation() throws Exception {
        var account = SessionAuthenticationTest.account("REPORTER");
        var browser = login(account);
        try (var connection = DriverManager.getConnection(SessionAuthenticationTest.db.url(),
                SessionAuthenticationTest.db.user(), SessionAuthenticationTest.db.password());
             var update = connection.prepareStatement("UPDATE public.app_accounts SET active=false,auth_generation=auth_generation+1 WHERE id=?")) {
            update.setObject(1, account.id());
            assertEquals(1, update.executeUpdate());
        }
        SessionAuthenticationTest.error(browser.get("/api/auth/me"), 403, "FORBIDDEN");
    }

    @Test void protectedUnsafeRequestsAuthenticateBeforeCsrfEvaluation() throws Exception {
        var anonymous = new SessionAuthenticationTest.Browser(server);
        SessionAuthenticationTest.error(anonymous.request("POST", "/api/developer/reports", "{}"),
                401, "UNAUTHENTICATED");

        var account = SessionAuthenticationTest.account("DEVELOPER");
        var browser = login(account);
        try (var connection = DriverManager.getConnection(SessionAuthenticationTest.db.url(),
                SessionAuthenticationTest.db.user(), SessionAuthenticationTest.db.password());
             var update = connection.prepareStatement("UPDATE public.app_accounts SET auth_generation=auth_generation+1 WHERE id=?")) {
            update.setObject(1, account.id());
            assertEquals(1, update.executeUpdate());
        }
        SessionAuthenticationTest.error(browser.request("POST", "/api/developer/reports", "{}"),
                401, "UNAUTHENTICATED");
    }

    @Test void unsupportedAuthMethodsReturnJson405AndAllowHeader() throws Exception {
        var anonymous = new SessionAuthenticationTest.Browser(server);
        var loginGet = anonymous.get("/api/auth/login");
        SessionAuthenticationTest.status(405, loginGet, "GET login");
        assertTrue(loginGet.headers().firstValue("Allow").orElse("").contains("POST"));
        SessionAuthenticationTest.error(loginGet, 405, "METHOD_NOT_ALLOWED");

        var account = SessionAuthenticationTest.account("REPORTER");
        var browser = login(account);
        var wrongMethod = browser.request("PUT", "/api/auth/me", "", "X-CSRF-TOKEN", browser.csrf());
        SessionAuthenticationTest.status(405, wrongMethod, "PUT me");
        assertTrue(wrongMethod.headers().firstValue("Allow").orElse("").contains("GET"));
        SessionAuthenticationTest.error(wrongMethod, 405, "METHOD_NOT_ALLOWED");
    }

    @Test void missingMaintainerApiIsSafe404AndKeepsSessionValid() throws Exception {
        var account = SessionAuthenticationTest.account("MAINTAINER");
        var browser = login(account);
        var missing = browser.get("/api/maintainer/not-a-real-route");
        SessionAuthenticationTest.error(missing, 404, "NOT_FOUND");
        SessionAuthenticationTest.identity(browser.get("/api/auth/me"), account);
    }

    private static SessionAuthenticationTest.Browser login(SessionAuthenticationTest.Account account) throws Exception {
        var browser = new SessionAuthenticationTest.Browser(server);
        SessionAuthenticationTest.status(200, browser.login(account, account.password(), browser.csrf()), "real login");
        return browser;
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(SessionAuthenticationTest.db.url(),
                SessionAuthenticationTest.db.user(), SessionAuthenticationTest.db.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
