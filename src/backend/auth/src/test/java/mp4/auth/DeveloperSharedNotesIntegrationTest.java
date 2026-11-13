package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises explicitly published Developer notes through authenticated HTTP and PostgreSQL. */
class DeveloperSharedNotesIntegrationTest {
    @BeforeAll static void startAuthenticatedServer() throws Exception {
        SessionAuthenticationTest.setup();
        try (var c = SessionAuthenticationTest.connection(); var statement = c.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS developer_private_notes ("
                    + "report_id uuid PRIMARY KEY REFERENCES reports(id), text text NOT NULL, "
                    + "revision bigint NOT NULL CHECK (revision >= 0))");
            statement.execute("CREATE TABLE IF NOT EXISTS shared_notes_test_write_gate (report_id uuid PRIMARY KEY)");
            statement.execute("""
                    CREATE OR REPLACE FUNCTION shared_notes_test_write_gate_fn() RETURNS trigger AS $$
                    BEGIN
                      PERFORM report_id FROM shared_notes_test_write_gate
                        WHERE report_id = NEW.report_id FOR UPDATE;
                      RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            statement.execute("DROP TRIGGER IF EXISTS shared_notes_test_write_gate ON report_shared_notes");
            statement.execute("""
                    CREATE TRIGGER shared_notes_test_write_gate
                    BEFORE INSERT OR UPDATE ON report_shared_notes
                    FOR EACH ROW EXECUTE FUNCTION shared_notes_test_write_gate_fn()
                    """);
        }
    }

    @AfterAll static void stopAuthenticatedServer() { SessionAuthenticationTest.shutdown(); }

    @Test void savedDraftStaysPrivateUntilExplicitShareAndMaintainerSeesOnlyPublishedNote() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id());
        createPrivateNote(report, "Private investigation marker");
        createExistingSharedNote(report, reporter.id(), "Existing shared evidence marker");

        var dev = SessionAuthenticationTest.authenticated(developer);
        var reporterBrowser = SessionAuthenticationTest.authenticated(reporter);
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        String draftPath = draftPath(report);
        var initial = dev.get(draftPath);
        SessionAuthenticationTest.status(200, initial, "assigned Developer reads shared-note draft");
        assertEquals(Map.of("text", "", "draftRevision", 0, "reportVersion", 0),
                SessionAuthenticationTest.json(initial));
        noStore(initial);

        String note = "Shared investigation finding marker";
        var saved = put(dev, draftPath, note, 0, dev.csrf());
        SessionAuthenticationTest.status(200, saved, "Developer saves shared-note draft");
        assertEquals(Map.of("draftRevision", 1), SessionAuthenticationTest.json(saved));
        noStore(saved);
        var afterSave = dev.get(draftPath);
        SessionAuthenticationTest.status(200, afterSave, "read saved shared-note draft");
        noStore(afterSave);
        assertEquals(Map.of("text", note, "draftRevision", 1, "reportVersion", 0),
                SessionAuthenticationTest.json(afterSave));
        assertEquals("Private investigation marker", privateNoteText(report));
        assertEquals(0, reportVersion(report), "private Save must not change report version");

        var beforeShare = maintainerBrowser.get(maintainerPath(report));
        SessionAuthenticationTest.status(200, beforeShare, "Maintainer reads report before Share");
        assertTrue(beforeShare.body().contains("Existing shared evidence marker"));
        assertFalse(beforeShare.body().contains(note), "a saved draft is not visible to Maintainers");
        assertFalse(beforeShare.body().contains("Private investigation marker"));

        var shared = post(dev, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":0}", dev.csrf());
        SessionAuthenticationTest.status(200, shared, "Developer explicitly shares saved note");
        assertEquals(Map.of("reportVersion", 1, "changed", true), SessionAuthenticationTest.json(shared));
        noStore(shared);
        assertEquals(1, reportVersion(report));
        assertHistory(report, developer.id(), 1, 1);
        assertEquals(2, sharedNoteCount(report), "Share must preserve unrelated existing shared evidence");
        assertEquals(developer.id(), publishedNoteAuthor(report, note), "publication author comes from the session");

        var unchanged = post(dev, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":1}", dev.csrf());
        SessionAuthenticationTest.status(200, unchanged, "sharing identical saved text is a no-op");
        assertEquals(Map.of("reportVersion", 1, "changed", false), SessionAuthenticationTest.json(unchanged));
        assertEquals(1, reportVersion(report));
        assertEquals(2, sharedNoteCount(report));
        assertHistory(report, developer.id(), 1, 1);

        var afterShare = maintainerBrowser.get(maintainerPath(report));
        SessionAuthenticationTest.status(200, afterShare, "Maintainer reads published note");
        assertTrue(afterShare.body().contains("Shared investigation finding marker"));
        assertTrue(afterShare.body().contains("Existing shared evidence marker"));
        assertFalse(afterShare.body().contains("Private investigation marker"));

        var savedB = put(dev, draftPath, "Unpublished finding B", 1, dev.csrf());
        SessionAuthenticationTest.status(200, savedB, "save a second note without publishing");
        var afterSaveB = maintainerBrowser.get(maintainerPath(report));
        SessionAuthenticationTest.status(200, afterSaveB, "Maintainer detail after saving unpublished B");
        assertTrue(afterSaveB.body().contains("Shared investigation finding marker"));
        assertFalse(afterSaveB.body().contains("Unpublished finding B"));
        assertEquals(1, reportVersion(report), "private Save B must not change report version");

        var sharedB = post(dev, sharePath(report),
                "{\"expectedDraftRevision\":2,\"expectedReportVersion\":1}", dev.csrf());
        SessionAuthenticationTest.status(200, sharedB, "share saved B");
        assertEquals(Map.of("reportVersion", 2, "changed", true), SessionAuthenticationTest.json(sharedB));
        assertHistory(report, developer.id(), 2, 2);
        assertEquals(developer.id(), publishedNoteAuthor(report, "Unpublished finding B"));
        var afterShareB = maintainerBrowser.get(maintainerPath(report));
        SessionAuthenticationTest.status(200, afterShareB, "Maintainer detail after sharing B");
        assertTrue(afterShareB.body().contains("Unpublished finding B"));
        assertFalse(afterShareB.body().contains("Shared investigation finding marker"));
        assertEquals(2, historyCount(report), "private Save B must not add shared history");
        var reporterDetail = reporterBrowser.get("/api/reporter/reports/" + report);
        SessionAuthenticationTest.status(200, reporterDetail, "Reporter reads owned report detail");
        assertFalse(reporterDetail.body().contains("Shared investigation finding marker"));
        assertFalse(reporterDetail.body().contains("Unpublished finding B"));
        assertFalse(reporterDetail.body().contains("Private investigation marker"));

        SessionAuthenticationTest.error(reporterBrowser.get(draftPath), 403, "FORBIDDEN");
        var reporterSave = put(reporterBrowser, draftPath, "Reporter attempt", 2, reporterBrowser.csrf());
        SessionAuthenticationTest.error(reporterSave, 403, "FORBIDDEN");
        assertFalse(reporterSave.body().contains("Reporter attempt"));
        var reporterShare = post(reporterBrowser, sharePath(report),
                "{\"expectedDraftRevision\":2,\"expectedReportVersion\":2}", reporterBrowser.csrf());
        SessionAuthenticationTest.error(reporterShare, 403, "FORBIDDEN");
        assertFalse(reporterShare.body().contains("Unpublished finding B"));
        assertEquals("Unpublished finding B", publishedNoteText(report, "Unpublished finding B"));
        assertEquals("Private investigation marker", privateNoteText(report));
    }

    @Test void unrelatedDeveloperAndMaintainerCannotReadSaveOrShareDraft() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var unrelatedDeveloper = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id());
        var assigned = SessionAuthenticationTest.authenticated(developer);
        String path = draftPath(report);

        SessionAuthenticationTest.status(200, put(assigned, path, "Published canary A", 0, assigned.csrf()), "Save A");
        SessionAuthenticationTest.status(200, post(assigned, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":0}", assigned.csrf()), "Share A");
        SessionAuthenticationTest.status(200, put(assigned, path, "Private draft canary B", 1, assigned.csrf()), "Save B privately");

        var unrelated = SessionAuthenticationTest.authenticated(unrelatedDeveloper);
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        for (var browser : java.util.List.of(unrelated, maintainerBrowser)) {
            assertDeniedWithoutSharedNote(browser.get(path), "Published canary A", "Private draft canary B");
            assertDeniedWithoutSharedNote(put(browser, path, "Unauthorized overwrite", 2, browser.csrf()),
                    "Published canary A", "Private draft canary B", "Unauthorized overwrite");
            assertDeniedWithoutSharedNote(post(browser, sharePath(report),
                    "{\"expectedDraftRevision\":2,\"expectedReportVersion\":1}", browser.csrf()),
                    "Published canary A", "Private draft canary B");
        }
        var unrelatedDetail = unrelated.get("/api/developer/reports/" + report);
        assertTrue(unrelatedDetail.statusCode() == 403 || unrelatedDetail.statusCode() == 404);
        assertFalse(unrelatedDetail.body().contains("Published canary A"));
        assertFalse(unrelatedDetail.body().contains("Private draft canary B"));

        var detail = maintainerBrowser.get(maintainerPath(report));
        SessionAuthenticationTest.status(200, detail, "Maintainer detail after denied direct draft requests");
        assertTrue(detail.body().contains("Published canary A"));
        assertFalse(detail.body().contains("Private draft canary B"));
        assertEquals(Map.of("text", "Private draft canary B", "draftRevision", 2, "reportVersion", 1),
                SessionAuthenticationTest.json(assigned.get(path)));
        assertEquals(1, reportVersion(report));
        assertEquals(1, sharedNoteCount(report));
        assertEquals("Published canary A", publishedNoteText(report, "Published canary A"));
    }

    @Test void inactiveAssignedDeveloperCannotReadSaveOrShare() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id());
        var browser = SessionAuthenticationTest.authenticated(developer);
        SessionAuthenticationTest.status(200, put(browser, draftPath(report), "Published canary", 0,
                browser.csrf()), "Save before deactivation");
        SessionAuthenticationTest.status(200, post(browser, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":0}", browser.csrf()), "Share before deactivation");
        String csrf = browser.csrf();
        SessionAuthenticationTest.update(developer, "active=false");

        SessionAuthenticationTest.error(browser.get(draftPath(report)), 403, "FORBIDDEN");
        SessionAuthenticationTest.error(browser.request("PUT", draftPath(report),
                "{\"text\":\"Inactive overwrite\",\"expectedDraftRevision\":1}",
                "Content-Type", "application/json", "X-CSRF-TOKEN", csrf), 401, "UNAUTHENTICATED");
        SessionAuthenticationTest.error(browser.request("POST", sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":1}",
                "Content-Type", "application/json", "X-CSRF-TOKEN", csrf), 401, "UNAUTHENTICATED");
        assertEquals(1, reportVersion(report));
        assertEquals(1, sharedNoteCount(report));
        assertEquals("Published canary", publishedNoteText(report, "Published canary"));
    }

    @Test void assignedDeveloperSaveAndShareRequireCsrf() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id());
        var browser = SessionAuthenticationTest.authenticated(developer);

        SessionAuthenticationTest.error(browser.request("PUT", draftPath(report),
                "{\"text\":\"CSRF blocked\",\"expectedDraftRevision\":0}", "Content-Type", "application/json"),
                403, "CSRF_INVALID");
        SessionAuthenticationTest.error(browser.request("POST", sharePath(report),
                "{\"expectedDraftRevision\":0,\"expectedReportVersion\":0}", "Content-Type", "application/json"),
                403, "CSRF_INVALID");
        assertEquals(0, reportVersion(report));
        assertEquals(0, sharedNoteCount(report));
    }

    @Test void emptyDraftCannotBeShared() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id());
        var dev = SessionAuthenticationTest.authenticated(developer);

        var draft = dev.get(draftPath(report));
        SessionAuthenticationTest.status(200, draft, "read empty shared-note draft");
        assertEquals(Map.of("text", "", "draftRevision", 0, "reportVersion", 0), SessionAuthenticationTest.json(draft));
        SessionAuthenticationTest.error(post(dev, sharePath(report),
                "{\"expectedDraftRevision\":0,\"expectedReportVersion\":0}", dev.csrf()),
                409, "EMPTY_DRAFT");
        var savedEmpty = put(dev, draftPath(report), "", 0, dev.csrf());
        SessionAuthenticationTest.status(200, savedEmpty, "empty draft may be saved privately");
        assertEquals(Map.of("draftRevision", 1), SessionAuthenticationTest.json(savedEmpty));
        SessionAuthenticationTest.error(post(dev, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":0}", dev.csrf()),
                409, "EMPTY_DRAFT");
        assertEquals(0, sharedNoteCount(report));
        assertEquals(0, reportVersion(report));
    }

    @Test void invalidTextAndUnsafeWireRevisionsAreRejectedWithoutMutation() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id());
        var dev = SessionAuthenticationTest.authenticated(developer);
        String path = draftPath(report);

        var nul = putJson(dev, path, "{\"text\":\"bad\\u0000text\",\"expectedDraftRevision\":0}");
        SessionAuthenticationTest.error(nul, 400, "VALIDATION_FAILED");
        var invalidUnicode = putJson(dev, path,
                "{\"text\":\"\\uD800\",\"expectedDraftRevision\":0}");
        SessionAuthenticationTest.error(invalidUnicode, 400, "VALIDATION_FAILED");
        var tooLong = put(dev, path, "x".repeat(10_001), 0, dev.csrf());
        SessionAuthenticationTest.error(tooLong, 400, "VALIDATION_FAILED");
        var unsafeSaveRevision = putJson(dev, path,
                "{\"text\":\"valid text\",\"expectedDraftRevision\":9007199254740992}");
        SessionAuthenticationTest.error(unsafeSaveRevision, 400, "VALIDATION_FAILED");
        var unsafeShareRevision = post(dev, sharePath(report),
                "{\"expectedDraftRevision\":9007199254740992,\"expectedReportVersion\":0}", dev.csrf());
        SessionAuthenticationTest.error(unsafeShareRevision, 400, "VALIDATION_FAILED");
        var unsafeShareReportVersion = post(dev, sharePath(report),
                "{\"expectedDraftRevision\":0,\"expectedReportVersion\":9007199254740992}", dev.csrf());
        SessionAuthenticationTest.error(unsafeShareReportVersion, 400, "VALIDATION_FAILED");
        assertEquals(0, reportVersion(report));
        var unchanged = dev.get(path);
        SessionAuthenticationTest.status(200, unchanged, "draft remains unchanged after invalid requests");
        assertEquals(Map.of("text", "", "draftRevision", 0, "reportVersion", 0), SessionAuthenticationTest.json(unchanged));
        var boundary = put(dev, path, "x".repeat(10_000), 0, dev.csrf());
        SessionAuthenticationTest.status(200, boundary, "10,000 UTF-16 code units are accepted");
        var boundaryRead = dev.get(path);
        SessionAuthenticationTest.status(200, boundaryRead, "read maximum-length draft");
        assertEquals(10_000, ((String) SessionAuthenticationTest.json(boundaryRead).get("text")).length());
    }

    @Test void changedShareAtMaximumSafeReportVersionDoesNotOverflow() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        long maxSafeInteger = 9_007_199_254_740_991L;
        createReport(report, reporter.id(), developer.id(), "ASSIGNED", maxSafeInteger);
        var dev = SessionAuthenticationTest.authenticated(developer);
        String path = draftPath(report);
        SessionAuthenticationTest.status(200, put(dev, path, "At version limit", 0, dev.csrf()), "save at report version limit");
        SessionAuthenticationTest.error(post(dev, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":9007199254740991}", dev.csrf()),
                409, "VERSION_LIMIT");
        assertEquals(maxSafeInteger, reportVersion(report));
        assertEquals(0, sharedNoteCount(report));
    }

    @Test void shareWinningCloseRaceMakesCloseStale() throws Exception {
        var f = prepareReviewFixture();
        try (WriteGate gate = holdSharedNoteGate(f.report())) {
            var share = asyncPost(f.developerBrowser(), sharePath(f.report()),
                    "{\"expectedDraftRevision\":2,\"expectedReportVersion\":2}");
            int sharePid = awaitBlockedWriter(gate.backendPid(), "report_shared_notes");
            var close = asyncPost(f.maintainerBrowser(), "/api/maintainer/reports/" + f.report() + "/close",
                    closeBody(f, 2));
            awaitBlockedRequest(sharePid);

            gate.release();
            var shared = share.get(15, TimeUnit.SECONDS);
            SessionAuthenticationTest.status(200, shared, "Share commits before close");
            assertEquals(Map.of("reportVersion", 3, "changed", true), SessionAuthenticationTest.json(shared));
            SessionAuthenticationTest.error(close.get(15, TimeUnit.SECONDS), 409, "STALE_VERSION");
        }
        assertEquals(Map.of("status", "IN_PROGRESS", "version", 3L, "proposal", f.proposal().toString()),
                reportState(f.report()));
        assertEquals("SUPERSEDED", proposalStatus(f.proposal()));
        assertEquals("Race draft B", publishedNoteText(f.report(), "Race draft B"));
    }

    @Test void closeWinningRaceRejectsShareAndPreservesPublishedNote() throws Exception {
        var f = prepareReviewFixture();
        var closeBody = closeBody(f, 2);
        try (ReportLock lock = holdReportLock(f.report())) {
            var close = asyncPost(f.maintainerBrowser(), "/api/maintainer/reports/" + f.report() + "/close", closeBody);
            awaitBlockedRequest(lock.backendPid());
            var share = asyncPost(f.developerBrowser(), sharePath(f.report()),
                    "{\"expectedDraftRevision\":2,\"expectedReportVersion\":2}");
            awaitBlockedRequests(lock.backendPid(), 2);

            lock.release();
            SessionAuthenticationTest.status(200, close.get(15, TimeUnit.SECONDS), "close commits before Share");
            var deniedShare = share.get(15, TimeUnit.SECONDS);
            assertTrue(deniedShare.statusCode() == 409,
                    "Share after close must be rejected with a conflict");
            String code = (String) SessionAuthenticationTest.json(deniedShare).get("code");
            assertTrue(Set.of("STALE_VERSION", "INVALID_STATE").contains(code));
        }
        assertEquals("CLOSED", reportStatus(f.report()));
        assertEquals(3, reportVersion(f.report()));
        assertEquals("CLOSED", proposalStatus(f.proposal()));
        assertEquals("Race draft A", publishedNoteText(f.report(), "Race draft A"));
        assertFalse(hasPublishedText(f.report(), "Race draft B"));
    }

    @Test void shareWinningReassignmentRaceMakesReassignmentStale() throws Exception {
        var f = prepareAssignmentFixture();
        try (WriteGate gate = holdSharedNoteGate(f.report())) {
            var share = asyncPost(f.developerBrowser(), sharePath(f.report()),
                    "{\"expectedDraftRevision\":2,\"expectedReportVersion\":1}");
            int sharePid = awaitBlockedWriter(gate.backendPid(), "report_shared_notes");
            var reassign = asyncPost(f.maintainerBrowser(), "/api/maintainer/reports/" + f.report() + "/assignment",
                    "{\"expectedVersion\":1,\"developerId\":\"" + f.nextDeveloper().id()
                            + "\",\"reason\":\"Rebalance investigation\"}");
            awaitBlockedRequest(sharePid);

            gate.release();
            SessionAuthenticationTest.status(200, share.get(15, TimeUnit.SECONDS), "Share commits before reassignment");
            SessionAuthenticationTest.error(reassign.get(15, TimeUnit.SECONDS), 409, "STALE_VERSION");
        }
        assertEquals("ASSIGNED", reportStatus(f.report()));
        assertEquals(2, reportVersion(f.report()));
        assertEquals(f.developer().id(), assigneeId(f.report()));
        assertEquals("Race draft B", publishedNoteText(f.report(), "Race draft B"));
    }

    @Test void reassignmentWinningRaceDeniesFormerAssigneeShare() throws Exception {
        var f = prepareAssignmentFixture();
        try (ReportLock lock = holdReportLock(f.report())) {
            var reassign = asyncPost(f.maintainerBrowser(), "/api/maintainer/reports/" + f.report() + "/assignment",
                    "{\"expectedVersion\":1,\"developerId\":\"" + f.nextDeveloper().id()
                            + "\",\"reason\":\"Rebalance investigation\"}");
            awaitBlockedRequest(lock.backendPid());
            var share = asyncPost(f.developerBrowser(), sharePath(f.report()),
                    "{\"expectedDraftRevision\":2,\"expectedReportVersion\":1}");
            awaitBlockedRequests(lock.backendPid(), 2);

            lock.release();
            SessionAuthenticationTest.status(200, reassign.get(15, TimeUnit.SECONDS), "reassignment commits before Share");
            var deniedShare = share.get(15, TimeUnit.SECONDS);
            assertTrue(deniedShare.statusCode() == 403 || deniedShare.statusCode() == 404,
                    "former assignee Share must be denied after reassignment");
            assertFalse(deniedShare.body().contains("Race draft B"));
        }
        assertEquals(f.nextDeveloper().id(), assigneeId(f.report()));
        assertEquals(2, reportVersion(f.report()));
        assertEquals("Race draft A", publishedNoteText(f.report(), "Race draft A"));
        assertFalse(hasPublishedText(f.report(), "Race draft B"));
        var next = SessionAuthenticationTest.authenticated(f.nextDeveloper());
        var inherited = next.get(draftPath(f.report()));
        SessionAuthenticationTest.status(200, inherited, "new assignee reads retained saved draft");
        assertEquals("Race draft B", SessionAuthenticationTest.json(inherited).get("text"));
    }

    @Test void PostgreSqlGateBlocksASharedNoteWriteUntilReleased() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id());
        try (WriteGate gate = holdSharedNoteGate(report)) {
            var insert = CompletableFuture.runAsync(() -> {
                try (var c = SessionAuthenticationTest.connection(); var statement = c.prepareStatement(
                        "INSERT INTO report_shared_notes(id,report_id,author_id,text) VALUES(?,?,?,?)")) {
                    statement.setObject(1, UUID.randomUUID()); statement.setObject(2, report);
                    statement.setObject(3, developer.id()); statement.setString(4, "Gate control row");
                    statement.executeUpdate();
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            awaitBlockedWriter(gate.backendPid(), "INSERT INTO report_shared_notes");
            assertFalse(insert.isDone(), "the test trigger holds the note write until the gate commits");
            gate.release();
            insert.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, sharedNoteCount(report));
    }

    @Test void staleDraftSaveAndStaleSharePreservePublishedNoteAndReportVersion() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id());
        var dev = SessionAuthenticationTest.authenticated(developer);
        String draftPath = draftPath(report);

        SessionAuthenticationTest.status(200, put(dev, draftPath, "Previously published A", 0, dev.csrf()), "Save A");
        SessionAuthenticationTest.status(200, post(dev, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":0}", dev.csrf()), "Share A");
        SessionAuthenticationTest.status(200, put(dev, draftPath, "Saved draft B", 1, dev.csrf()), "Save B");
        SessionAuthenticationTest.error(put(dev, draftPath, "Stale overwrite", 1, dev.csrf()),
                409, "STALE_VERSION");
        var afterStale = dev.get(draftPath);
        SessionAuthenticationTest.status(200, afterStale, "read draft after stale Save");
        assertEquals(Map.of("text", "Saved draft B", "draftRevision", 2, "reportVersion", 1),
                SessionAuthenticationTest.json(afterStale));
        SessionAuthenticationTest.error(post(dev, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":1}", dev.csrf()),
                409, "STALE_VERSION");
        SessionAuthenticationTest.error(post(dev, sharePath(report),
                "{\"expectedDraftRevision\":2,\"expectedReportVersion\":0}", dev.csrf()),
                409, "STALE_VERSION");
        assertEquals(1, sharedNoteCount(report));
        assertEquals("Previously published A", publishedNoteText(report, "Previously published A"));
        assertFalse(hasPublishedText(report, "Saved draft B"));
        assertEquals(1, reportVersion(report));
    }

    @Test void changedShareSupersedesProposalAndMakesMaintainerCloseStale() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        UUID report = UUID.randomUUID();
        UUID proposal = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id(), "RESOLUTION_PROPOSED", 7);
        createProposal(report, proposal, "PENDING", 3);

        var dev = SessionAuthenticationTest.authenticated(developer);
        String draftPath = draftPath(report);
        var reviewDraft = dev.get(draftPath);
        SessionAuthenticationTest.status(200, reviewDraft, "assigned Developer reads draft during review");
        assertEquals(Map.of("text", "", "draftRevision", 0, "reportVersion", 7),
                SessionAuthenticationTest.json(reviewDraft));
        SessionAuthenticationTest.status(200, put(dev, draftPath, "Updated investigation finding", 0, dev.csrf()),
                "save note during review");
        assertEquals(Map.of("status", "RESOLUTION_PROPOSED", "version", 7L, "proposal", proposal.toString()),
                reportState(report));
        assertEquals("PENDING", proposalStatus(proposal));
        assertEquals(0, historyCount(report), "private Save during review must not append shared history");
        var shared = post(dev, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":7}", dev.csrf());
        SessionAuthenticationTest.status(200, shared, "changed Share during review");
        assertEquals(Map.of("reportVersion", 8, "changed", true), SessionAuthenticationTest.json(shared));
        assertEquals(Map.of("status", "IN_PROGRESS", "version", 8L, "proposal", proposal.toString()),
                reportState(report));
        assertEquals("SUPERSEDED", proposalStatus(proposal));
        assertHistory(report, developer.id(), 8, 1);

        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        var staleClose = post(maintainerBrowser, "/api/maintainer/reports/" + report + "/close",
                "{\"expectedVersion\":7,\"proposalId\":\"" + proposal
                        + "\",\"proposalVersion\":3,\"reviewed\":true}", maintainerBrowser.csrf());
        SessionAuthenticationTest.error(staleClose, 409, "STALE_VERSION");
        assertEquals(Map.of("status", "IN_PROGRESS", "version", 8L, "proposal", proposal.toString()),
                reportState(report));
        assertEquals("SUPERSEDED", proposalStatus(proposal));
        assertEquals("Updated investigation finding", publishedNoteText(report, "Updated investigation finding"));
    }

    @Test void reassignmentMovesDraftAccessAndClosedReportIsReadableButNotWritable() throws Exception {
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        var firstDeveloper = SessionAuthenticationTest.account("DEVELOPER");
        var nextDeveloper = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), firstDeveloper.id());
        var firstBrowser = SessionAuthenticationTest.authenticated(firstDeveloper);
        String draftPath = draftPath(report);
        SessionAuthenticationTest.status(200, put(firstBrowser, draftPath, "Retained shared-note draft", 0,
                firstBrowser.csrf()), "first Developer saves draft");

        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        var reassigned = post(maintainerBrowser, "/api/maintainer/reports/" + report + "/assignment",
                "{\"expectedVersion\":0,\"developerId\":\"" + nextDeveloper.id()
                        + "\",\"reason\":\"Move to current investigator\"}",
                maintainerBrowser.csrf());
        SessionAuthenticationTest.status(200, reassigned, "Maintainer reassigns report");
        var formerRead = firstBrowser.get(draftPath);
        assertTrue(formerRead.statusCode() == 403 || formerRead.statusCode() == 404,
                "former assignee must lose access after reassignment");
        assertFalse(formerRead.body().contains("Retained shared-note draft"));
        var formerSave = put(firstBrowser, draftPath, "Former assignee overwrite", 1, firstBrowser.csrf());
        assertDeniedWithoutSharedNote(formerSave, "Retained shared-note draft", "Former assignee overwrite");
        var formerShare = post(firstBrowser, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":1}", firstBrowser.csrf());
        assertDeniedWithoutSharedNote(formerShare, "Retained shared-note draft");
        var nextBrowser = SessionAuthenticationTest.authenticated(nextDeveloper);
        var initialForNewAssignee = nextBrowser.get(draftPath);
        SessionAuthenticationTest.status(200, initialForNewAssignee, "new assignee inherits draft access");
        assertEquals(Map.of("text", "Retained shared-note draft", "draftRevision", 1, "reportVersion", 1),
                SessionAuthenticationTest.json(initialForNewAssignee));
        var published = post(nextBrowser, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":1}", nextBrowser.csrf());
        SessionAuthenticationTest.status(200, published, "new assignee shares inherited draft");
        assertEquals(Map.of("reportVersion", 2, "changed", true), SessionAuthenticationTest.json(published));

        setReportClosed(report);
        var closedRead = nextBrowser.get(draftPath);
        SessionAuthenticationTest.status(200, closedRead, "assigned Developer may read draft after closure");
        assertEquals("Retained shared-note draft", SessionAuthenticationTest.json(closedRead).get("text"));
        SessionAuthenticationTest.error(put(nextBrowser, draftPath, "Closed draft overwrite", 1,
                nextBrowser.csrf()), 409, "INVALID_STATE");
        SessionAuthenticationTest.error(post(nextBrowser, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":2}", nextBrowser.csrf()),
                409, "INVALID_STATE");
        assertEquals("Retained shared-note draft", SessionAuthenticationTest.json(nextBrowser.get(draftPath)).get("text"));
        assertEquals("Retained shared-note draft", publishedNoteText(report, "Retained shared-note draft"));
        assertEquals(2, reportVersion(report));
    }

    @Test void assignedDeveloperCanReadDraftOnClosedReport() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id(), "CLOSED", 5);
        var dev = SessionAuthenticationTest.authenticated(developer);

        var read = dev.get(draftPath(report));
        SessionAuthenticationTest.status(200, read, "assigned Developer reads draft after closure");
        assertEquals(Map.of("text", "", "draftRevision", 0, "reportVersion", 5),
                SessionAuthenticationTest.json(read));
        noStore(read);
    }

    private static HttpResponse<String> put(SessionAuthenticationTest.Browser browser, String path,
            String text, long revision, String csrf) throws Exception {
        String body = "{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"expectedDraftRevision\":" + revision + "}";
        return browser.request("PUT", path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
    }

    private static HttpResponse<String> putJson(SessionAuthenticationTest.Browser browser, String path,
            String body) throws Exception {
        return browser.request("PUT", path, body, "Content-Type", "application/json",
                "X-CSRF-TOKEN", browser.csrf());
    }

    private static HttpResponse<String> post(SessionAuthenticationTest.Browser browser, String path,
            String body, String csrf) throws Exception {
        return browser.request("POST", path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", csrf);
    }

    private static void createReport(UUID id, UUID reporter, UUID assignee) throws Exception {
        createReport(id, reporter, assignee, "ASSIGNED", 0);
    }

    private static void createReport(UUID id, UUID reporter, UUID assignee, String status, long version) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            insert.setObject(1, id); insert.setString(2, "Shared notes integration");
            insert.setString(3, "Disposable acceptance fixture"); insert.setObject(4, reporter);
            insert.setObject(5, assignee); insert.setString(6, status); insert.setLong(7, version); insert.executeUpdate();
        }
    }

    private static void createProposal(UUID report, UUID proposal, String status, long version) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO resolution_proposals(id,report_id,status,version,contents) VALUES(?,?,?,?,?)")) {
            insert.setObject(1, proposal); insert.setObject(2, report); insert.setString(3, status);
            insert.setLong(4, version); insert.setString(5, "Current reviewed resolution"); insert.executeUpdate();
        }
        try (var c = SessionAuthenticationTest.connection(); var update = c.prepareStatement(
                "UPDATE reports SET current_proposal_id=? WHERE id=?")) {
            update.setObject(1, proposal); update.setObject(2, report); assertEquals(1, update.executeUpdate());
        }
    }

    private static Map<String, Object> reportState(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT status,version,current_proposal_id FROM reports WHERE id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return Map.of("status", rows.getString(1), "version", rows.getLong(2),
                        "proposal", rows.getObject(3).toString());
            }
        }
    }

    private static String proposalStatus(UUID proposal) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT status FROM resolution_proposals WHERE id=?")) {
            query.setObject(1, proposal); try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static void assertHistory(UUID report, UUID actor, long expectedVersion, int expectedCount) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT actor_id,report_version FROM report_history WHERE report_id=? ORDER BY report_version")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                int count = 0;
                long latestVersion = -1;
                while (rows.next()) {
                    assertEquals(actor, rows.getObject(1, UUID.class));
                    latestVersion = rows.getLong(2);
                    count++;
                }
                assertEquals(expectedCount, count);
                if (expectedCount > 0) assertEquals(expectedVersion, latestVersion);
            }
        }
    }

    private static long historyCount(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT count(*) FROM report_history WHERE report_id=?")) {
            query.setObject(1, report); try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    private static void setReportClosed(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var update = c.prepareStatement(
                "UPDATE reports SET status='CLOSED' WHERE id=?")) {
            update.setObject(1, report); assertEquals(1, update.executeUpdate());
        }
    }

    private record RaceFixture(UUID report, SessionAuthenticationTest.Account developer,
            SessionAuthenticationTest.Account nextDeveloper, SessionAuthenticationTest.Account reporter,
            SessionAuthenticationTest.Account maintainer, SessionAuthenticationTest.Browser developerBrowser,
            SessionAuthenticationTest.Browser maintainerBrowser, UUID proposal, long proposalVersion) {}

    private static RaceFixture prepareReviewFixture() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var nextDeveloper = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id(), "IN_PROGRESS", 0);
        var developerBrowser = SessionAuthenticationTest.authenticated(developer);
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        SessionAuthenticationTest.status(200, put(developerBrowser, draftPath(report), "Race draft A", 0,
                developerBrowser.csrf()), "save published race fixture A");
        SessionAuthenticationTest.status(200, post(developerBrowser, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":0}", developerBrowser.csrf()),
                "publish race fixture A");
        SessionAuthenticationTest.status(200, post(developerBrowser, "/api/developer/reports/" + report + "/resolution",
                "{\"expectedVersion\":1,\"resolution\":\"Investigated and ready for review\"}",
                developerBrowser.csrf()), "propose resolution for close race");
        UUID proposal;
        long proposalVersion;
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT p.id,p.version FROM reports r JOIN resolution_proposals p ON p.id=r.current_proposal_id WHERE r.id=?")) {
            query.setObject(1, report);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); proposal = rows.getObject(1, UUID.class); proposalVersion = rows.getLong(2);
            }
        }
        SessionAuthenticationTest.status(200, put(developerBrowser, draftPath(report), "Race draft B", 1,
                developerBrowser.csrf()), "save unpublished race fixture B");
        return new RaceFixture(report, developer, nextDeveloper, reporter, maintainer,
                developerBrowser, maintainerBrowser, proposal, proposalVersion);
    }

    private static RaceFixture prepareAssignmentFixture() throws Exception {
        var developer = SessionAuthenticationTest.account("DEVELOPER");
        var nextDeveloper = SessionAuthenticationTest.account("DEVELOPER");
        var reporter = SessionAuthenticationTest.account("REPORTER");
        var maintainer = SessionAuthenticationTest.account("MAINTAINER");
        UUID report = UUID.randomUUID();
        createReport(report, reporter.id(), developer.id());
        var developerBrowser = SessionAuthenticationTest.authenticated(developer);
        var maintainerBrowser = SessionAuthenticationTest.authenticated(maintainer);
        SessionAuthenticationTest.status(200, put(developerBrowser, draftPath(report), "Race draft A", 0,
                developerBrowser.csrf()), "save published reassignment fixture A");
        SessionAuthenticationTest.status(200, post(developerBrowser, sharePath(report),
                "{\"expectedDraftRevision\":1,\"expectedReportVersion\":0}", developerBrowser.csrf()),
                "publish reassignment fixture A");
        SessionAuthenticationTest.status(200, put(developerBrowser, draftPath(report), "Race draft B", 1,
                developerBrowser.csrf()), "save unpublished reassignment fixture B");
        return new RaceFixture(report, developer, nextDeveloper, reporter, maintainer,
                developerBrowser, maintainerBrowser, null, 0);
    }

    private static String closeBody(RaceFixture fixture, long expectedVersion) {
        return "{\"expectedVersion\":" + expectedVersion + ",\"proposalId\":\"" + fixture.proposal()
                + "\",\"proposalVersion\":" + fixture.proposalVersion() + ",\"reviewed\":true}";
    }

    private static CompletableFuture<HttpResponse<String>> asyncPost(
            SessionAuthenticationTest.Browser browser, String path, String body) throws Exception {
        String csrf = browser.csrf();
        return CompletableFuture.supplyAsync(() -> {
            try { return post(browser, path, body, csrf); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
    }

    private static WriteGate holdSharedNoteGate(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO shared_notes_test_write_gate(report_id) VALUES(?) ON CONFLICT DO NOTHING")) {
            insert.setObject(1, report); insert.executeUpdate();
        }
        var c = SessionAuthenticationTest.connection();
        c.setAutoCommit(false);
        int pid;
        try (var query = c.prepareStatement("SELECT pg_backend_pid()")) {
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); pid = rows.getInt(1); }
        }
        try (var lock = c.prepareStatement(
                "SELECT report_id FROM shared_notes_test_write_gate WHERE report_id=? FOR UPDATE")) {
            lock.setObject(1, report); try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
        }
        return new WriteGate(c, pid);
    }

    private static ReportLock holdReportLock(UUID report) throws Exception {
        var c = SessionAuthenticationTest.connection();
        c.setAutoCommit(false);
        int pid;
        try (var query = c.prepareStatement("SELECT pg_backend_pid()")) {
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); pid = rows.getInt(1); }
        }
        try (var lock = c.prepareStatement("SELECT id FROM reports WHERE id=? FOR UPDATE")) {
            lock.setObject(1, report); try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
        }
        return new ReportLock(c, pid);
    }

    private static int awaitBlockedWriter(int blockerPid, String queryPart) throws Exception {
        return waitForBlockedBackend(blockerPid, queryPart, false);
    }

    private static void awaitBlockedRequest(int blockerPid) throws Exception {
        waitForBlockedBackend(blockerPid, null, false);
    }

    private static void awaitBlockedRequests(int blockerPid, int minimum) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                    "WITH RECURSIVE lock_chain(waiter_pid,blocker_pid) AS ("
                            + "SELECT activity.pid, blocker.blocker_pid FROM pg_stat_activity activity "
                            + "CROSS JOIN LATERAL unnest(pg_blocking_pids(activity.pid)) blocker(blocker_pid) "
                            + "WHERE activity.datname=current_database() AND activity.wait_event_type='Lock' "
                            + "UNION SELECT lock_chain.waiter_pid, blocker.blocker_pid FROM lock_chain "
                            + "CROSS JOIN LATERAL unnest(pg_blocking_pids(lock_chain.blocker_pid)) blocker(blocker_pid)) "
                            + "SELECT count(DISTINCT waiter_pid) FROM lock_chain WHERE blocker_pid=?")) {
                query.setInt(1, blockerPid);
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); if (rows.getInt(1) >= minimum) return; }
            }
            Thread.sleep(10);
        }
        fail("expected " + minimum + " requests blocked by PostgreSQL backend " + blockerPid);
    }

    private static int waitForBlockedBackend(int blockerPid, String queryPart, boolean unused) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                    "SELECT activity.pid FROM pg_stat_activity activity WHERE activity.datname=current_database() "
                            + "AND activity.wait_event_type='Lock' AND (?=ANY(pg_blocking_pids(activity.pid))) "
                            + "AND (? IS NULL OR activity.query ILIKE ?) LIMIT 1")) {
                query.setInt(1, blockerPid);
                if (queryPart == null) { query.setNull(2, java.sql.Types.VARCHAR); query.setNull(3, java.sql.Types.VARCHAR); }
                else { query.setString(2, queryPart); query.setString(3, "%" + queryPart + "%"); }
                try (var rows = query.executeQuery()) { if (rows.next()) return rows.getInt(1); }
            }
            Thread.sleep(10);
        }
        fail("request did not block on PostgreSQL backend " + blockerPid);
        return -1;
    }

    private record WriteGate(Connection connection, int backendPid) implements AutoCloseable {
        void release() throws Exception { connection.commit(); }
        public void close() throws Exception { if (!connection.isClosed()) { connection.rollback(); connection.close(); } }
    }

    private record ReportLock(Connection connection, int backendPid) implements AutoCloseable {
        void release() throws Exception { connection.commit(); }
        public void close() throws Exception { if (!connection.isClosed()) { connection.rollback(); connection.close(); } }
    }

    private static void createPrivateNote(UUID report, String text) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO developer_private_notes(report_id,text,revision) VALUES(?,?,1)")) {
            insert.setObject(1, report); insert.setString(2, text); insert.executeUpdate();
        }
    }

    private static void createExistingSharedNote(UUID report, UUID author, String text) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var insert = c.prepareStatement(
                "INSERT INTO report_shared_notes(id,report_id,author_id,text) VALUES(?,?,?,?)")) {
            insert.setObject(1, UUID.randomUUID()); insert.setObject(2, report);
            insert.setObject(3, author); insert.setString(4, text); insert.executeUpdate();
        }
    }

    private static String draftPath(UUID report) {
        return "/api/developer/reports/" + report + "/shared-notes/draft";
    }
    private static String sharePath(UUID report) {
        return "/api/developer/reports/" + report + "/shared-notes/share";
    }
    private static String maintainerPath(UUID report) { return "/api/maintainer/reports/" + report; }

    private static String privateNoteText(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT text FROM developer_private_notes WHERE report_id=?")) {
            query.setObject(1, report); try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static String publishedNoteText(UUID report, String text) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT text FROM report_shared_notes WHERE report_id=? AND text=?")) {
            query.setObject(1, report); query.setString(2, text);
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static UUID publishedNoteAuthor(UUID report, String text) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT author_id FROM report_shared_notes WHERE report_id=? AND text=?")) {
            query.setObject(1, report); query.setString(2, text);
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getObject(1, UUID.class); }
        }
    }

    private static int sharedNoteCount(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT count(*) FROM report_shared_notes WHERE report_id=?")) {
            query.setObject(1, report); try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
        }
    }

    private static long reportVersion(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT version FROM reports WHERE id=?")) {
            query.setObject(1, report); try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    private static String reportStatus(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT status FROM reports WHERE id=?")) {
            query.setObject(1, report); try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static UUID assigneeId(UUID report) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT assignee_id FROM reports WHERE id=?")) {
            query.setObject(1, report); try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getObject(1, UUID.class); }
        }
    }

    private static boolean hasPublishedText(UUID report, String text) throws Exception {
        try (var c = SessionAuthenticationTest.connection(); var query = c.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM report_shared_notes WHERE report_id=? AND text=?)")) {
            query.setObject(1, report); query.setString(2, text);
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getBoolean(1); }
        }
    }

    private static void noStore(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    private static void assertDeniedWithoutSharedNote(HttpResponse<String> response, String... canaries) {
        assertTrue(response.statusCode() == 403 || response.statusCode() == 404,
                "actor without current Developer access must be denied");
        assertFalse(response.body().contains("draftRevision"));
        assertFalse(response.body().contains("reportVersion"));
        for (String canary : canaries) assertFalse(response.body().contains(canary));
    }
}
