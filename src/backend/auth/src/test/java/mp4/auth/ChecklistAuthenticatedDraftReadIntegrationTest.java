package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises the private draft read through the real session/security chain. */
class ChecklistAuthenticatedDraftReadIntegrationTest {
    @BeforeAll static void startAuthenticatedServer() throws Exception {
        SessionAuthenticationTest.setup();
    }

    @AfterAll static void stopAuthenticatedServer() {
        SessionAuthenticationTest.shutdown();
    }

    @Test void currentAssigneeReadsOnlyDraftAndMissingChecklistDefaultsToEmptyRevisionZero() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var withChecklist = UUID.randomUUID();
        var withoutChecklist = UUID.randomUUID();
        report(withChecklist, reporter.id(), developer.id());
        checklist(withChecklist, new String[] {"Private draft canary"}, 3, new String[] {"Shared canary"});
        report(withoutChecklist, reporter.id(), developer.id());

        var browser = SessionAuthenticationTest.authenticated(developer);
        var response = browser.get(path(withChecklist));
        SessionAuthenticationTest.status(200, response, "assigned Developer draft read");
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(Map.of("steps", List.of("Private draft canary"), "draftRevision", 3),
                SessionAuthenticationTest.json(response));
        assertFalse(response.body().contains("Shared canary"));

        var empty = browser.get(path(withoutChecklist));
        SessionAuthenticationTest.status(200, empty, "assigned Developer missing checklist read");
        assertTrue(empty.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(Map.of("steps", List.of(), "draftRevision", 0), SessionAuthenticationTest.json(empty));
    }

    @Test void reassignmentAndRoleEligibilityPreventDraftDisclosure() throws Exception {
        var first = SessionAuthenticationTest.account("DEVELOPER");
        var second = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var report = UUID.randomUUID();
        report(report, reporter.id(), first.id());
        checklist(report, new String[] {"Do not disclose draft"}, 4, new String[] {"Published steps"});
        var firstBrowser = SessionAuthenticationTest.authenticated(first);
        var secondBrowser = SessionAuthenticationTest.authenticated(second);
        var reporterBrowser = SessionAuthenticationTest.authenticated(reporter);
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);

        var initiallyAssigned = firstBrowser.get(path(report));
        SessionAuthenticationTest.status(200, initiallyAssigned, "currently assigned Developer draft read");
        assertEquals(Map.of("steps", List.of("Do not disclose draft"), "draftRevision", 4),
                SessionAuthenticationTest.json(initiallyAssigned));
        denyWithoutDraft(secondBrowser.get(path(report)));
        denyWithoutDraft(reporterBrowser.get(path(report)));
        denyWithoutDraft(maintainerBrowser.get(path(report)));

        try (var c = SessionAuthenticationTest.connection();
             var update = c.prepareStatement("UPDATE reports SET assignee_id=? WHERE id=?")) {
            update.setObject(1, second.id()); update.setObject(2, report); assertEquals(1, update.executeUpdate());
        }
        denyWithoutDraft(firstBrowser.get(path(report)));
        var newlyAssigned = secondBrowser.get(path(report));
        SessionAuthenticationTest.status(200, newlyAssigned, "newly assigned Developer draft read");
        assertEquals(Map.of("steps", List.of("Do not disclose draft"), "draftRevision", 4),
                SessionAuthenticationTest.json(newlyAssigned));
    }

    @Test void unauthenticatedAndInactiveAccountsCannotReadDraft() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = UUID.randomUUID();
        report(report, reporter.id(), developer.id());
        checklist(report, new String[] {"Do not disclose draft"}, 1, new String[] {"Published steps"});

        var anonymous = new SessionAuthenticationTest.Browser(SessionAuthenticationTest.app);
        var unauthenticated = anonymous.get(path(report));
        SessionAuthenticationTest.error(unauthenticated, 401, "UNAUTHENTICATED");
        assertFalse(unauthenticated.body().contains("Do not disclose draft"));

        var inactive = SessionAuthenticationTest.authenticated(developer);
        SessionAuthenticationTest.update(developer, "active=false");
        var response = inactive.get(path(report));
        SessionAuthenticationTest.error(response, 403, "FORBIDDEN");
        assertFalse(response.body().contains("Do not disclose draft"));
    }

    private static void denyWithoutDraft(HttpResponse<String> response) {
        SessionAuthenticationTest.error(response, 403, "FORBIDDEN");
        assertFalse(response.body().contains("Do not disclose draft"));
    }

    private static String path(UUID report) { return "/api/reports/" + report + "/checklist/draft"; }

    private static void report(UUID id, UUID reporter, UUID assignee) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO reports(id,title,description,reporter_id,assignee_id,status) VALUES(?,?,?,?,?,'ASSIGNED')")) {
            insert.setObject(1, id); insert.setString(2, "Checklist draft read integration");
            insert.setString(3, "Disposable acceptance fixture"); insert.setObject(4, reporter);
            insert.setObject(5, assignee); insert.executeUpdate();
        }
    }

    private static void checklist(UUID report, String[] draft, long revision, String[] shared) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?::text[],?,?::text[])")) {
            insert.setObject(1, report); insert.setString(2, pgArray(draft)); insert.setLong(3, revision);
            insert.setString(4, pgArray(shared)); insert.executeUpdate();
        }
    }

    private static String pgArray(String[] values) {
        if (values.length == 0) return "{}";
        return "{" + String.join(",", java.util.Arrays.stream(values)
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList()) + "}";
    }
}
