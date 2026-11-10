package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Proposed Maintainer API contracts exercised through real sessions and PostgreSQL fixtures. */
class MaintainerAuthenticatedWorkflowIntegrationTest {
    @BeforeAll static void startAuthenticatedServer() throws Exception {
        SessionAuthenticationTest.setup();
    }

    @AfterAll static void stopAuthenticatedServer() {
        SessionAuthenticationTest.shutdown();
    }

    @Test void retainedProposalHistoryIncludesItsIdentityAndVersion() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var report = report(reporter.id(), developer.id(), "CLOSED", 4);
        var retained = proposal(report, "CLOSED", 2, "Retained resolution evidence");
        setCurrentProposal(report, retained);
        var browser = SessionAuthenticationTest.authenticated(maintainer);
        var response = browser.get("/api/maintainer/reports/" + report);
        SessionAuthenticationTest.status(200, response, "retained proposal detail");
        var body = SessionAuthenticationTest.json(response);
        var history = (List<?>) body.get("history");
        assertTrue(history.stream().map(event -> (Map<?, ?>) event).anyMatch(event ->
                ((String) event.get("text")).contains(retained.toString())
                && ((String) event.get("text")).contains("version 2")
                && ((String) event.get("text")).contains("Retained resolution evidence")));
    }

    @Test void reassignmentRevokesFormerDeveloperReadsAndWrites() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var former = SessionAuthenticationTest.account("DEVELOPER");
        var replacement = SessionAuthenticationTest.account("DEVELOPER");
        var report = report(reporter.id(), former.id(), "IN_PROGRESS", 3);
        checklist(report, new String[]{"Private draft"}, 1, new String[]{"Published evidence"});
        var browser = SessionAuthenticationTest.authenticated(maintainer);
        SessionAuthenticationTest.status(200, post(browser, "/api/maintainer/reports/" + report + "/assignment",
                "{\"expectedVersion\":3,\"developerId\":\"" + replacement.id() + "\",\"reason\":\"Rebalance work\"}"), "reassignment");
        var oldSession = SessionAuthenticationTest.authenticated(former);
        String draftPath = "/api/reports/" + report + "/checklist/draft";
        SessionAuthenticationTest.error(oldSession.get(draftPath), 403, "FORBIDDEN");
        SessionAuthenticationTest.error(put(oldSession, draftPath, "{\"expectedDraftRevision\":1,\"steps\":[\"Overwrite\"]}"), 403, "FORBIDDEN");
        var currentSession = SessionAuthenticationTest.authenticated(replacement);
        SessionAuthenticationTest.status(200, currentSession.get(draftPath), "new assignee reads preserved draft");
        var detail = browser.get("/api/maintainer/reports/" + report);
        assertTrue(detail.body().contains("Published evidence"));
        assertFalse(detail.body().contains("Private draft"));
    }

    @Test void activeMaintainerReadsReportsDevelopersAndOnlySharedChecklistProjection() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var report = report(reporter.id(), developer.id(), "ASSIGNED", 4);
        checklist(report, new String[] {"Private unsaved step"}, 9, new String[] {"Published step"});
        var browser = SessionAuthenticationTest.authenticated(maintainer);

        var list = browser.get("/api/maintainer/reports?assigneeId=" + developer.id() + "&limit=20&offset=0");
        SessionAuthenticationTest.status(200, list, "active Maintainer report list");
        assertTrue(list.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var page = SessionAuthenticationTest.json(list);
        assertEquals(Set.of("items", "total"), page.keySet());
        assertEquals(1, ((Number) page.get("total")).intValue());
        assertTrue(list.body().contains(report.toString()));

        var detail = browser.get("/api/maintainer/reports/" + report);
        SessionAuthenticationTest.status(200, detail, "active Maintainer report detail");
        assertTrue(detail.body().contains("Published step"));
        assertFalse(detail.body().contains("Private unsaved step"));
        assertFalse(detail.body().contains("draftRevision"));
        assertFalse(detail.body().contains("draft_revision"));
        assertFalse(detail.body().contains("privateNotes"));

        var developers = browser.get("/api/maintainer/developers");
        SessionAuthenticationTest.status(200, developers, "active Developer summaries");
        assertTrue(developers.body().contains(developer.id().toString()));
        assertFalse(developers.body().contains(developer.username()));
    }

    @Test void realSessionRoleCsrfAndSpoofControlsProtectMaintainerApi() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var reportOwner = SessionAuthenticationTest.account("REPORTER");
        var report = report(reportOwner.id(), null, "SUBMITTED", 0);
        String path = "/api/maintainer/reports/" + report + "/priority";
        String body = "{\"expectedVersion\":0,\"priority\":\"HIGH\"}";

        var anonymous = new SessionAuthenticationTest.Browser(SessionAuthenticationTest.app);
        SessionAuthenticationTest.error(anonymous.get("/api/maintainer/reports"), 401, "UNAUTHENTICATED");
        assertFalse(anonymous.get("/api/maintainer/reports/" + report).body().contains("Private"));

        var wrongRole = SessionAuthenticationTest.authenticated(reporter);
        SessionAuthenticationTest.error(wrongRole.get("/api/maintainer/reports"), 403, "FORBIDDEN");
        var missingCsrf = wrongRole.request("PUT", path, body, "Content-Type", "application/json");
        SessionAuthenticationTest.error(missingCsrf, 403, "CSRF_INVALID");

        var browser = SessionAuthenticationTest.authenticated(maintainer);
        var spoofed = browser.request("PUT", path,
                "{\"expectedVersion\":0,\"priority\":\"HIGH\",\"actorId\":\"" + maintainer.id()
                        + "\",\"role\":\"MAINTAINER\"}",
                "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf(),
                "X-User-Id", reportOwner.id().toString(), "X-Role", "REPORTER");
        SessionAuthenticationTest.error(spoofed, 400, "VALIDATION_FAILED");
        assertEquals(0, reportVersion(report));
    }

    @Test void priorityCompareAndSetUpdatesOnlyTheCurrentAggregateAndRejectsStaleRetry() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = report(reporter.id(), null, "SUBMITTED", 2);
        var browser = SessionAuthenticationTest.authenticated(maintainer);
        String path = "/api/maintainer/reports/" + report + "/priority";

        var changed = put(browser, path, "{\"expectedVersion\":2,\"priority\":\"CRITICAL\"}");
        SessionAuthenticationTest.status(200, changed, "versioned priority change");
        assertEquals("CRITICAL", SessionAuthenticationTest.json(changed).get("priority"));
        assertEquals(3, ((Number) SessionAuthenticationTest.json(changed).get("version")).intValue());

        var stale = put(browser, path, "{\"expectedVersion\":2,\"priority\":\"LOW\"}");
        SessionAuthenticationTest.error(stale, 409, "STALE_VERSION");
        assertEquals(3, reportVersion(report));
    }

    @Test void assignmentAndReassignmentRequireEligibleTargetsAndPreserveVersionOnStaleWrite() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var firstDeveloper = SessionAuthenticationTest.account("DEVELOPER");
        var secondDeveloper = SessionAuthenticationTest.account("DEVELOPER");
        var inactiveDeveloper = SessionAuthenticationTest.account("DEVELOPER");
        SessionAuthenticationTest.update(inactiveDeveloper, "active=false");
        var report = report(reporter.id(), null, "SUBMITTED", 0);
        var browser = SessionAuthenticationTest.authenticated(maintainer);
        String path = "/api/maintainer/reports/" + report + "/assignment";

        var assigned = post(browser, path, "{\"expectedVersion\":0,\"developerId\":\"" + firstDeveloper.id() + "\"}");
        SessionAuthenticationTest.status(200, assigned, "initial assignment");
        assertEquals(firstDeveloper.id().toString(), ((Map<?, ?>) SessionAuthenticationTest.json(assigned).get("assignee")).get("id"));
        assertEquals("Assigned", SessionAuthenticationTest.json(assigned).get("status"));

        var stale = post(browser, path, "{\"expectedVersion\":0,\"developerId\":\"" + secondDeveloper.id() + "\"}");
        SessionAuthenticationTest.error(stale, 409, "STALE_VERSION");
        assertEquals(1, reportVersion(report));

        var inactive = post(browser, path, "{\"expectedVersion\":1,\"developerId\":\"" + inactiveDeveloper.id() + "\"}");
        SessionAuthenticationTest.error(inactive, 400, "VALIDATION_FAILED");
        var reassigned = post(browser, path, "{\"expectedVersion\":1,\"developerId\":\"" + secondDeveloper.id()
                + "\",\"reason\":\"Rebalance workload\"}");
        SessionAuthenticationTest.status(200, reassigned, "reasoned reassignment");
        assertEquals(secondDeveloper.id().toString(), ((Map<?, ?>) SessionAuthenticationTest.json(reassigned).get("assignee")).get("id"));
        assertEquals(2, reportVersion(report));
    }

    @Test void returnRetainsProposalAndReopenClearsCurrentAssignmentAndProposal() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var report = report(reporter.id(), developer.id(), "RESOLUTION_PROPOSED", 7);
        var proposal = proposal(report, "PENDING", 3, "Retry after restoring the connection");
        setCurrentProposal(report, proposal);
        var browser = SessionAuthenticationTest.authenticated(maintainer);

        var returned = post(browser, "/api/maintainer/reports/" + report + "/resolution/return",
                "{\"expectedVersion\":7,\"proposalId\":\"" + proposal + "\",\"proposalVersion\":3,\"reason\":\"Need one more trace\"}");
        SessionAuthenticationTest.status(200, returned, "return current proposal");
        assertEquals("In progress", SessionAuthenticationTest.json(returned).get("status"));
        assertEquals("SUPERSEDED", proposalStatus(proposal));
        assertEquals(8, reportVersion(report));

        var closedReport = report(reporter.id(), developer.id(), "CLOSED", 12);
        var closedProposal = proposal(closedReport, "CLOSED", 2, "Resolved with retry");
        setCurrentProposal(closedReport, closedProposal);
        var reopened = post(browser, "/api/maintainer/reports/" + closedReport + "/reopen",
                "{\"expectedVersion\":12,\"reason\":\"New evidence arrived\"}");
        SessionAuthenticationTest.status(200, reopened, "reasoned reopen");
        assertEquals("Submitted", SessionAuthenticationTest.json(reopened).get("status"));
        assertEquals(null, SessionAuthenticationTest.json(reopened).get("assignee"));
        assertEquals(13, reportVersion(closedReport));
        assertEquals("CLOSED", proposalStatus(closedProposal));
    }

    @Test void closureRequiresExplicitReviewAndTheCurrentProposalVersion() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var report = report(reporter.id(), developer.id(), "RESOLUTION_PROPOSED", 5);
        var proposal = proposal(report, "PENDING", 4, "Patch verified");
        setCurrentProposal(report, proposal);
        var browser = SessionAuthenticationTest.authenticated(maintainer);
        String path = "/api/maintainer/reports/" + report + "/close";

        var unreviewed = post(browser, path, "{\"expectedVersion\":5,\"proposalId\":\"" + proposal
                + "\",\"proposalVersion\":4,\"reviewed\":false}");
        SessionAuthenticationTest.error(unreviewed, 400, "VALIDATION_FAILED");
        assertEquals(5, reportVersion(report));

        var staleProposal = post(browser, path, "{\"expectedVersion\":5,\"proposalId\":\"" + proposal
                + "\",\"proposalVersion\":3,\"reviewed\":true}");
        SessionAuthenticationTest.error(staleProposal, 409, "STALE_VERSION");
        assertEquals(5, reportVersion(report));

        var closed = post(browser, path, "{\"expectedVersion\":5,\"proposalId\":\"" + proposal
                + "\",\"proposalVersion\":4,\"reviewed\":true}");
        SessionAuthenticationTest.status(200, closed, "explicitly reviewed close");
        assertEquals("Closed", SessionAuthenticationTest.json(closed).get("status"));
        assertEquals("CLOSED", proposalStatus(proposal));
        assertEquals(6, reportVersion(report));
    }

    @Test void plainTextDiscussionDoesNotChangeLifecycleAndClosedReportCommentsAreAllowed() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = report(reporter.id(), null, "CLOSED", 3);
        var browser = SessionAuthenticationTest.authenticated(maintainer);
        String text = "<script>plain text only</script>\nPlease retain this note.";

        var response = post(browser, "/api/maintainer/reports/" + report + "/comments",
                "{\"expectedVersion\":3,\"text\":\"" + jsonEscape(text) + "\"}");
        SessionAuthenticationTest.status(201, response, "comment on Closed report");
        var result = SessionAuthenticationTest.json(response);
        assertEquals(text, ((Map<?, ?>) result.get("comment")).get("text"));
        assertEquals(4, ((Number) result.get("reportVersion")).intValue());
        assertEquals("CLOSED", reportStatus(report));
        assertEquals(4, reportVersion(report));
    }

    @Test void staleAndMalformedWritesLeaveReportAndProposalRowsUnchanged() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var report = report(reporter.id(), developer.id(), "RESOLUTION_PROPOSED", 6);
        var proposal = proposal(report, "PENDING", 1, "Current proposal");
        setCurrentProposal(report, proposal);
        var browser = SessionAuthenticationTest.authenticated(maintainer);

        var stale = post(browser, "/api/maintainer/reports/" + report + "/resolution/return",
                "{\"expectedVersion\":5,\"proposalId\":\"" + proposal + "\",\"proposalVersion\":1,\"reason\":\"Return\"}");
        SessionAuthenticationTest.error(stale, 409, "STALE_VERSION");
        var unknownField = post(browser, "/api/maintainer/reports/" + report + "/resolution/return",
                "{\"expectedVersion\":6,\"proposalId\":\"" + proposal + "\",\"proposalVersion\":1,\"reason\":\"Return\",\"status\":\"CLOSED\"}");
        SessionAuthenticationTest.error(unknownField, 400, "VALIDATION_FAILED");
        var c0Control = post(browser, "/api/maintainer/reports/" + report + "/resolution/return",
                "{\"expectedVersion\":6,\"proposalId\":\"" + proposal + "\",\"proposalVersion\":1,\"reason\":\"bad\\u0001reason\"}");
        SessionAuthenticationTest.error(c0Control, 400, "VALIDATION_FAILED");
        var c1Control = post(browser, "/api/maintainer/reports/" + report + "/resolution/return",
                "{\"expectedVersion\":6,\"proposalId\":\"" + proposal + "\",\"proposalVersion\":1,\"reason\":\"bad\\u0085reason\"}");
        SessionAuthenticationTest.error(c1Control, 400, "VALIDATION_FAILED");
        assertEquals("RESOLUTION_PROPOSED", reportStatus(report));
        assertEquals(6, reportVersion(report));
        assertEquals("PENDING", proposalStatus(proposal));
    }

    @Test void malformedUtf8RequestBodyIsRejectedWithoutMutation() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var report = report(reporter.id(), null, "SUBMITTED", 0);
        var browser = SessionAuthenticationTest.authenticated(maintainer);
        String prefix = "{\"expectedVersion\":0,\"text\":\"";
        byte[] beforeInvalidSequence = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] afterInvalidSequence = "\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] malformed = new byte[beforeInvalidSequence.length + 1 + afterInvalidSequence.length];
        System.arraycopy(beforeInvalidSequence, 0, malformed, 0, beforeInvalidSequence.length);
        malformed[beforeInvalidSequence.length] = (byte) 0xC3;
        System.arraycopy(afterInvalidSequence, 0, malformed, beforeInvalidSequence.length + 1, afterInvalidSequence.length);

        var response = requestBytes(browser, "POST", "/api/maintainer/reports/" + report + "/comments", malformed,
                "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());
        SessionAuthenticationTest.error(response, 400, "VALIDATION_FAILED");
        assertEquals(0, reportVersion(report));
        assertEquals("SUBMITTED", reportStatus(report));
    }

    @Test void reportVersionCannotAdvancePastJavaScriptSafeInteger() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        long maxSafeVersion = 9_007_199_254_740_991L;
        var report = report(reporter.id(), null, "SUBMITTED", maxSafeVersion);
        var browser = SessionAuthenticationTest.authenticated(maintainer);

        var response = put(browser, "/api/maintainer/reports/" + report + "/priority",
                "{\"expectedVersion\":" + maxSafeVersion + ",\"priority\":\"HIGH\"}");
        SessionAuthenticationTest.error(response, 409, "VERSION_LIMIT");
        assertEquals(maxSafeVersion, reportVersion(report));
        assertEquals("SUBMITTED", reportStatus(report));
    }

    private static HttpResponse<String> put(SessionAuthenticationTest.Browser browser, String path, String body) throws Exception {
        return browser.request("PUT", path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());
    }

    private static HttpResponse<String> post(SessionAuthenticationTest.Browser browser, String path, String body) throws Exception {
        return browser.request("POST", path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", browser.csrf());
    }

    private static HttpResponse<String> requestBytes(SessionAuthenticationTest.Browser browser, String method,
            String path, byte[] body, String... headers) throws Exception {
        var builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(browser.server.base + path))
                .timeout(java.time.Duration.ofSeconds(20));
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return browser.client.send(builder.method(method, java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private static UUID report(UUID reporter, UUID assignee, String status, long version) throws Exception {
        UUID id = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version) VALUES(?,?,?,?,?,?,?)")) {
            insert.setObject(1, id); insert.setString(2, "Maintainer API fixture");
            insert.setString(3, "Synthetic integration test report"); insert.setObject(4, reporter);
            insert.setObject(5, assignee); insert.setString(6, status); insert.setLong(7, version); insert.executeUpdate();
        }
        return id;
    }

    private static void checklist(UUID report, String[] draft, long revision, String[] shared) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES(?,?::text[],?,?::text[])")) {
            insert.setObject(1, report); insert.setString(2, pgArray(draft)); insert.setLong(3, revision);
            insert.setString(4, pgArray(shared)); insert.executeUpdate();
        }
    }

    /** The known five-column proposal contract is shared with Developer Share fixtures. */
    private static UUID proposal(UUID report, String status, long version, String contents) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS resolution_proposals (id uuid PRIMARY KEY, report_id uuid NOT NULL REFERENCES reports(id), status text NOT NULL, version bigint NOT NULL, contents text NOT NULL)");
        }
        UUID id = UUID.randomUUID();
        try (var c = SessionAuthenticationTest.connection();
             var insert = c.prepareStatement("INSERT INTO resolution_proposals(id,report_id,status,version,contents) VALUES(?,?,?,?,?)")) {
            insert.setObject(1, id); insert.setObject(2, report); insert.setString(3, status);
            insert.setLong(4, version); insert.setString(5, contents); insert.executeUpdate();
        }
        return id;
    }

    private static void setCurrentProposal(UUID report, UUID proposal) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var update = c.prepareStatement("UPDATE reports SET current_proposal_id=? WHERE id=?")) {
            update.setObject(1, proposal); update.setObject(2, report); assertEquals(1, update.executeUpdate());
        }
    }

    private static long reportVersion(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var query = c.prepareStatement("SELECT version FROM reports WHERE id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    private static String reportStatus(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var query = c.prepareStatement("SELECT status FROM reports WHERE id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static String proposalStatus(UUID proposal) throws Exception {
        try (var c = SessionAuthenticationTest.connection();
             var query = c.prepareStatement("SELECT status FROM resolution_proposals WHERE id=?")) {
            query.setObject(1, proposal);
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static String pgArray(String[] values) {
        if (values.length == 0) return "{}";
        return "{" + String.join(",", java.util.Arrays.stream(values)
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList()) + "}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
