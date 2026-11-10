package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 1.4 (report-discussion D7): the Reporter reports API is mounted in the deployed auth server
 * at /api/reporter/reports with the trusted session actor (RPT-06, contract revision 3).
 * Guard behaviour asserted (existing SessionGuardRegressionTest convention, same envelope as the contract):
 * no or revoked session is 401 UNAUTHENTICATED; inactive account or wrong role is 403 FORBIDDEN.
 */
class ReporterApiMountTest {
    private static final String BASE = "/api/reporter/reports";
    private static SessionAuthenticationTest.Server server;

    @BeforeAll static void start() throws Exception {
        SessionAuthenticationTest.db = ProvisioningDatabase.Config.fromEnvironment(Map.of(
                "MP4_AUTH_JDBC_URL", Objects.requireNonNull(System.getenv("MP4_AUTH_TEST_JDBC_URL")),
                "MP4_AUTH_DB_USER", Objects.requireNonNull(System.getenv("MP4_AUTH_TEST_DB_USER")),
                "MP4_AUTH_DB_PASSWORD", Objects.requireNonNull(System.getenv("MP4_AUTH_TEST_DB_PASSWORD"))));
        ProvisioningDatabase.initialize(SessionAuthenticationTest.db);
        server = new SessionAuthenticationTest.Server();
    }

    @AfterAll static void stop() { if (server != null) server.close(); }

    private static SessionAuthenticationTest.Browser login(SessionAuthenticationTest.Account a) throws Exception {
        var b = new SessionAuthenticationTest.Browser(server);
        SessionAuthenticationTest.status(200, b.login(a, a.password(), b.csrf()), "login");
        return b;
    }

    private static HttpResponse<String> send(SessionAuthenticationTest.Browser b, String method, String path,
            String body, String... extra) throws Exception {
        var headers = new ArrayList<>(List.of("Content-Type", "application/json", "X-CSRF-TOKEN", b.csrf()));
        headers.addAll(List.of(extra));
        return b.request(method, path, body, headers.toArray(String[]::new));
    }

    private static void noStore(HttpResponse<String> r) {
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    private static Map<String, Object> create(SessionAuthenticationTest.Browser b, String title) throws Exception {
        var r = send(b, "POST", BASE, "{\"title\":\"" + title + "\",\"description\":\"## Steps\\n1. Open\"}");
        SessionAuthenticationTest.status(201, r, "create");
        noStore(r);
        var data = SessionAuthenticationTest.json(r);
        assertEquals(BASE + "/" + data.get("id"), r.headers().firstValue("Location").orElse(null));
        return data;
    }

    // RPT-01..05, 09 through the real server: create, list, read, edit, and no close action.
    @Test void activeReporterCanCreateListReadEditAndHasNoCloseAction() throws Exception {
        var b = login(SessionAuthenticationTest.account("REPORTER"));
        var created = create(b, "Login fails");
        String id = (String) created.get("id");
        assertEquals("SUBMITTED", created.get("status"));
        assertEquals(0, created.get("version"));

        var list = b.get(BASE);
        SessionAuthenticationTest.status(200, list, "list");
        noStore(list);
        assertTrue(list.body().contains(id));

        var read = b.get(BASE + "/" + id);
        SessionAuthenticationTest.status(200, read, "read");
        noStore(read);
        assertEquals(id, SessionAuthenticationTest.json(read).get("id"));

        var edit = send(b, "PUT", BASE + "/" + id, "{\"title\":\"Edited\",\"description\":\"d\",\"version\":0}");
        SessionAuthenticationTest.status(200, edit, "edit");
        noStore(edit);
        var edited = SessionAuthenticationTest.json(edit);
        assertEquals("Edited", edited.get("title"));
        assertEquals(1, edited.get("version"));

        // RPT-09 "Reporters cannot change status": there is no close action; the sub-path is the read 404.
        var close = send(b, "POST", BASE + "/" + id + "/close", "{\"version\":1}");
        SessionAuthenticationTest.error(close, 404, "REPORT_NOT_FOUND");
        noStore(close);
        var after = SessionAuthenticationTest.json(b.get(BASE + "/" + id));
        assertEquals("SUBMITTED", after.get("status"));
        assertEquals(1, after.get("version"));
    }

    // RPT-06: identity in header, query or body never selects the actor; foreign read is a uniform 404.
    @Test void actorComesOnlyFromSessionAndForeignReadIsByteIdentical404() throws Exception {
        var r1 = SessionAuthenticationTest.account("REPORTER");
        var r2 = SessionAuthenticationTest.account("REPORTER");
        var b1 = login(r1);
        var b2 = login(r2);

        var forged = send(b1, "POST", BASE + "?reporterId=" + r2.id() + "&userId=" + r2.id(),
                "{\"title\":\"Mine\",\"description\":\"d\"}",
                "X-Reporter-Id", r2.id().toString(), "X-User-Id", r2.id().toString(), "X-Role", "REPORTER");
        SessionAuthenticationTest.status(201, forged, "create with forged identity headers and query");
        String id = (String) SessionAuthenticationTest.json(forged).get("id");
        try (var c = SessionAuthenticationTest.connection();
             var s = c.prepareStatement("SELECT reporter_id FROM reports WHERE id=?")) {
            s.setObject(1, UUID.fromString(id));
            try (var rows = s.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(r1.id(), rows.getObject(1, UUID.class), "reporter_id must be the session actor");
            }
        }
        var bodyIdentity = send(b1, "POST", BASE,
                "{\"title\":\"x\",\"description\":\"d\",\"reporterId\":\"" + r2.id() + "\"}");
        SessionAuthenticationTest.error(bodyIdentity, 400, "VALIDATION_FAILED");

        var spoofedRead = b2.request("GET", BASE + "/" + id + "?reporterId=" + r1.id(), "",
                "X-Reporter-Id", r1.id().toString());
        SessionAuthenticationTest.error(spoofedRead, 404, "REPORT_NOT_FOUND");
        var absent = b2.get(BASE + "/" + UUID.randomUUID());
        SessionAuthenticationTest.error(absent, 404, "REPORT_NOT_FOUND");
        assertEquals(absent.body().replaceAll("\"requestId\":\"[^\"]*\"", ""),
                spoofedRead.body().replaceAll("\"requestId\":\"[^\"]*\"", ""),
                "foreign and absent 404 bodies must be identical");
        assertFalse(b2.get(BASE).body().contains(id), "R2 list must not include R1's report");
    }

    // RPT-06 401/403: guard behaviour.
    @Test void anonymousDeveloperMaintainerInactiveAndRevokedAreRefused() throws Exception {
        SessionAuthenticationTest.error(new SessionAuthenticationTest.Browser(server).get(BASE), 401, "UNAUTHENTICATED");
        SessionAuthenticationTest.error(new SessionAuthenticationTest.Browser(server).request("GET", BASE, "",
                "X-Reporter-Id", UUID.randomUUID().toString()), 401, "UNAUTHENTICATED");
        for (String role : List.of("DEVELOPER", "MAINTAINER")) {
            var b = login(SessionAuthenticationTest.account(role));
            SessionAuthenticationTest.error(b.get(BASE), 403, "FORBIDDEN");
            SessionAuthenticationTest.error(send(b, "POST", BASE, "{\"title\":\"t\",\"description\":\"d\"}"),
                    403, "FORBIDDEN");
        }
        var inactive = SessionAuthenticationTest.account("REPORTER");
        var bi = login(inactive);
        SessionAuthenticationTest.status(200, bi.get(BASE), "active reporter reaches mounted route");
        SessionAuthenticationTest.update(inactive, "active=false");
        SessionAuthenticationTest.error(bi.get(BASE), 403, "FORBIDDEN");

        var revoked = SessionAuthenticationTest.account("REPORTER");
        var br = login(revoked);
        SessionAuthenticationTest.status(200, br.get(BASE), "active reporter reaches mounted route");
        SessionAuthenticationTest.update(revoked, "auth_generation=auth_generation+1");
        SessionAuthenticationTest.error(br.get(BASE), 401, "UNAUTHENTICATED");
    }

    // Server CSRF rule: POST and PUT without a valid token are refused.
    @Test void mutationsWithoutCsrfTokenAreRefused() throws Exception {
        var b = login(SessionAuthenticationTest.account("REPORTER"));
        String id = (String) create(b, "Seed").get("id");
        String body = "{\"title\":\"t\",\"description\":\"d\"}";
        String edit = "{\"title\":\"t\",\"description\":\"d\",\"version\":0}";
        SessionAuthenticationTest.error(b.request("POST", BASE, body, "Content-Type", "application/json"),
                403, "CSRF_INVALID");
        SessionAuthenticationTest.error(b.request("PUT", BASE + "/" + id, edit, "Content-Type", "application/json"),
                403, "CSRF_INVALID");
        assertEquals("Seed", SessionAuthenticationTest.json(b.get(BASE + "/" + id)).get("title"),
                "refused edit must not write");
    }

    // D7: shared migration 002 (created_at/updated_at + index) is applied by the deployed server's Flyway.
    @Test void reportTimestampMigrationIsAppliedByDeployedFlyway() throws Exception {
        try (Connection c = DriverManager.getConnection(SessionAuthenticationTest.db.url(),
                SessionAuthenticationTest.db.user(), SessionAuthenticationTest.db.password());
             var s = c.prepareStatement("SELECT count(*) FROM public.mp4_shared_schema_history "
                     + "WHERE success AND version IS NOT NULL AND CAST(version AS numeric) >= 4");
             var rows = s.executeQuery()) {
            assertTrue(rows.next());
            assertTrue(rows.getInt(1) >= 1, "shared Flyway history must have a successful version >= 4 (the 002 migration)");
        }
        try (Connection c = DriverManager.getConnection(SessionAuthenticationTest.db.url(),
                SessionAuthenticationTest.db.user(), SessionAuthenticationTest.db.password());
             var s = c.prepareStatement("SELECT count(*) FROM pg_indexes WHERE indexname='reports_reporter_created_idx'");
             var rows = s.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "reports_reporter_created_idx must exist");
        }
    }
}
