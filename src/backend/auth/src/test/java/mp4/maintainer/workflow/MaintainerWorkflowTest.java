package mp4.maintainer.workflow;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static mp4.maintainer.workflow.MaintainerContracts.*;

class MaintainerWorkflowTest {
    @Test void authorizedReadsReturnSafeSharedEvidenceAndActiveDevelopers() {
        var f = new WorkflowFixture();
        assertNotNull(f.service.listReports(f.actor, QueryFilters.defaults()));
        var detail = f.service.reportDetail(f.actor, WorkflowFixture.REPORT);
        assertNotNull(detail); assertEquals(List.of(f.note), detail.notes()); assertEquals(List.of(f.checklistItem), detail.checklist());
        assertEquals(List.of(f.response), detail.responses()); assertEquals(f.pending, detail.currentProposal());
        assertEquals(List.of(f.retained, f.pending), detail.proposalHistory()); assertEquals(List.of(f.priorEvent), detail.history());
        assertEquals(List.of(f.dev1, f.dev2), f.service.developers(f.actor));
    }
    @Test void wrongOrInactiveActorIsForbiddenBeforeRepositoryQueries() {
        for (Actor actor : List.of(new Actor(WorkflowFixture.MAINTAINER, Role.DEVELOPER, true, 7),
                new Actor(WorkflowFixture.MAINTAINER, Role.MAINTAINER, false, 7))) {
            var f = new WorkflowFixture();
            error(() -> f.service.listReports(actor, QueryFilters.defaults()), "FORBIDDEN", 403);
            error(() -> f.service.updatePriority(actor, WorkflowFixture.REPORT, new PriorityChange(8, Priority.HIGH)), "FORBIDDEN", 403);
            assertTrue(f.calls.isEmpty());
        }
    }
    @Test void priorityChangesOnlyMetadataAndPreservesUnrelatedState() {
        var f = new WorkflowFixture(); var out = f.service.updatePriority(f.actor, WorkflowFixture.REPORT, new PriorityChange(8, Priority.HIGH));
        assertEquals(Priority.HIGH, out.priority()); assertEquals(9, out.version()); assertEquals(Status.RESOLUTION_PROPOSED, f.report.status());
        assertEquals(WorkflowFixture.DEV1, f.report.assigneeId()); assertEquals(WorkflowFixture.PROPOSAL, f.report.currentProposalId());
        assertEquals(List.of(f.retained, f.pending), f.proposals); assertEquals(2, f.history.size());
        assertEquals(List.of(f.note), f.notes); assertEquals(List.of(f.checklistItem), f.checklist); assertEquals(List.of(f.response), f.responses);
        WorkflowFixture.assertLockOrder(f.calls);
    }
    @Test void stalePriorityIsRejectedWithoutAnyPartialChange() {
        var f = new WorkflowFixture(); error(() -> f.service.updatePriority(f.actor, WorkflowFixture.REPORT,
                new PriorityChange(7, Priority.HIGH)), "STALE_VERSION", 409);
        assertSame(f.initial, f.report); assertEquals(List.of(f.priorEvent), f.history);
    }
    @Test void submittedReportCanBeAssignedToAnActiveDeveloper() {
        var f = new WorkflowFixture(); f.report = withStatus(f.report, Status.SUBMITTED, null);
        var out = f.service.assign(f.actor, WorkflowFixture.REPORT, new AssignmentChange(8, WorkflowFixture.DEV2, null));
        assertEquals(Status.ASSIGNED, out.status()); assertEquals(WorkflowFixture.DEV2, f.report.assigneeId()); assertEquals(2, f.history.size());
        WorkflowFixture.assertLockOrder(f.calls);
    }
    @Test void reassignmentRequiresReasonDifferentActiveTargetAndEligibleState() {
        var ok = new WorkflowFixture(); ok.report = withStatus(ok.report, Status.IN_PROGRESS, WorkflowFixture.DEV1);
        var out = ok.service.assign(ok.actor, WorkflowFixture.REPORT, new AssignmentChange(8, WorkflowFixture.DEV2, "balancing work"));
        assertEquals(Status.ASSIGNED, out.status()); assertEquals(WorkflowFixture.DEV2, ok.report.assigneeId());
        assertEquals(List.of(ok.note), ok.notes); assertEquals(List.of(ok.checklistItem), ok.checklist); assertEquals(List.of(ok.response), ok.responses);
        assertEquals(List.of(ok.retained, ok.pending), ok.proposals); WorkflowFixture.assertLockOrder(ok.calls);
        var missing = new WorkflowFixture(); missing.report = withStatus(missing.report, Status.ASSIGNED, WorkflowFixture.DEV1);
        error(() -> missing.service.assign(missing.actor, WorkflowFixture.REPORT, new AssignmentChange(8, WorkflowFixture.DEV2, " ")), "VALIDATION_FAILED", 400);
        var same = new WorkflowFixture(); same.report = withStatus(same.report, Status.ASSIGNED, WorkflowFixture.DEV1);
        error(() -> same.service.assign(same.actor, WorkflowFixture.REPORT, new AssignmentChange(8, WorkflowFixture.DEV1, "same")), "INVALID_STATE", 409);
        var invalid = new WorkflowFixture(); error(() -> invalid.service.assign(invalid.actor, WorkflowFixture.REPORT,
                new AssignmentChange(8, WorkflowFixture.DEV2, "reason")), "INVALID_STATE", 409);
    }
    @Test void freshActorAndTargetEligibilityAreRecheckedInTransaction() {
        var inactive = new WorkflowFixture(); inactive.eligibility = Map.of(WorkflowFixture.MAINTAINER,
                new AccountEligibility(WorkflowFixture.MAINTAINER, Role.MAINTAINER, false, 7));
        error(() -> inactive.service.updatePriority(inactive.actor, WorkflowFixture.REPORT, new PriorityChange(8, Priority.HIGH)), "UNAUTHENTICATED", 401);
        var revoked = new WorkflowFixture(); revoked.eligibility = Map.of(WorkflowFixture.MAINTAINER,
                new AccountEligibility(WorkflowFixture.MAINTAINER, Role.MAINTAINER, true, 8));
        error(() -> revoked.service.updatePriority(revoked.actor, WorkflowFixture.REPORT, new PriorityChange(8, Priority.HIGH)), "UNAUTHENTICATED", 401);
        var wrongRoleActor = new WorkflowFixture(); wrongRoleActor.eligibility = Map.of(WorkflowFixture.MAINTAINER,
                new AccountEligibility(WorkflowFixture.MAINTAINER, Role.DEVELOPER, true, 7));
        error(() -> wrongRoleActor.service.updatePriority(wrongRoleActor.actor, WorkflowFixture.REPORT, new PriorityChange(8, Priority.HIGH)), "UNAUTHENTICATED", 401);
        var wrongRole = new WorkflowFixture(); wrongRole.report = withStatus(wrongRole.report, Status.SUBMITTED, null);
        error(() -> wrongRole.service.assign(wrongRole.actor, WorkflowFixture.REPORT, new AssignmentChange(8, WorkflowFixture.WRONG_ROLE, null)), "VALIDATION_FAILED", 400);
        var inactiveTarget = new WorkflowFixture(); inactiveTarget.report = withStatus(inactiveTarget.report, Status.SUBMITTED, null);
        error(() -> inactiveTarget.service.assign(inactiveTarget.actor, WorkflowFixture.REPORT, new AssignmentChange(8, WorkflowFixture.INACTIVE, null)), "VALIDATION_FAILED", 400);
    }
    @Test void returnSupersedesCurrentProposalAndRetainsItsHistory() {
        var f = new WorkflowFixture(); var out = f.service.returnResolution(f.actor, WorkflowFixture.REPORT,
                new ReturnResolution(8, WorkflowFixture.PROPOSAL, 3, "needs more evidence"));
        assertEquals(Status.IN_PROGRESS, out.status()); assertNull(f.report.currentProposalId());
        assertEquals(ProposalStatus.SUPERSEDED, f.proposals.stream().filter(p -> p.id().equals(WorkflowFixture.PROPOSAL)).findFirst().orElseThrow().status());
        assertEquals(2, f.proposals.size()); assertEquals(2, f.history.size());
        error(() -> new WorkflowFixture().service.returnResolution(new WorkflowFixture().actor, WorkflowFixture.REPORT,
                new ReturnResolution(8, WorkflowFixture.PROPOSAL, 2, "reason")), "STALE_VERSION", 409);
    }
    @Test void closeRequiresExplicitReviewAndExactCurrentProposalVersions() {
        var f = new WorkflowFixture(); error(() -> f.service.close(f.actor, WorkflowFixture.REPORT,
                new CloseReport(8, WorkflowFixture.PROPOSAL, 3, false)), "VALIDATION_FAILED", 400);
        assertSame(f.initial, f.report); assertEquals(List.of(f.priorEvent), f.history);
        var g = new WorkflowFixture(); var out = g.service.close(g.actor, WorkflowFixture.REPORT,
                new CloseReport(8, WorkflowFixture.PROPOSAL, 3, true));
        assertEquals(Status.CLOSED, out.status()); assertEquals(ProposalStatus.CLOSED, g.proposals.stream().filter(p -> p.id().equals(WorkflowFixture.PROPOSAL)).findFirst().orElseThrow().status());
        var stale = new WorkflowFixture(); error(() -> stale.service.close(stale.actor, WorkflowFixture.REPORT,
                new CloseReport(8, WorkflowFixture.PROPOSAL, 2, true)), "STALE_VERSION", 409);
        var superseded = new WorkflowFixture(); error(() -> superseded.service.close(superseded.actor, WorkflowFixture.REPORT,
                new CloseReport(8, WorkflowFixture.OLD_PROPOSAL, 2, true)), "STALE_VERSION", 409);
    }
    @Test void reopenRequiresReasonAndRetainsPriorProposalAndHistory() {
        var f = new WorkflowFixture(); f.report = withStatus(f.report, Status.CLOSED, WorkflowFixture.DEV1);
        var out = f.service.reopen(f.actor, WorkflowFixture.REPORT, new ReopenReport(8, "regression confirmed"));
        assertEquals(Status.SUBMITTED, out.status()); assertNull(f.report.assigneeId()); assertNull(f.report.currentProposalId());
        assertEquals(2, f.proposals.size()); assertEquals(2, f.history.size());
        error(() -> f.service.reopen(f.actor, WorkflowFixture.REPORT, new ReopenReport(8, " ")), "VALIDATION_FAILED", 400);
    }
    @Test void commentsOnClosedReportPreserveLifecycleAndProposal() {
        var f = new WorkflowFixture(); f.report = withStatus(f.report, Status.CLOSED, WorkflowFixture.DEV1);
        var out = f.service.addComment(f.actor, WorkflowFixture.REPORT, new AddComment(8, "Plain discussion\nsecond line"));
        assertEquals(9, out.reportVersion()); assertEquals(Status.CLOSED, f.report.status());
        assertEquals(WorkflowFixture.PROPOSAL, f.report.currentProposalId()); assertEquals(1, f.comments.size()); assertEquals(2, f.history.size());
        WorkflowFixture.assertLockOrder(f.calls);
    }
    @Test void commentsCountUnicodeCodePointsAndRejectInvalidText() {
        var f = new WorkflowFixture(); var out = f.service.addComment(f.actor, WorkflowFixture.REPORT, new AddComment(8, "🧭".repeat(4000)));
        assertEquals(9, out.reportVersion()); assertEquals(1, f.comments.size());
        var blank = new WorkflowFixture(); error(() -> blank.service.addComment(blank.actor, WorkflowFixture.REPORT, new AddComment(8, " \t\n ")), "VALIDATION_FAILED", 400);
        var control = new WorkflowFixture(); error(() -> control.service.addComment(control.actor, WorkflowFixture.REPORT, new AddComment(8, "bad\u0001text")), "VALIDATION_FAILED", 400);
        var longText = new WorkflowFixture(); error(() -> longText.service.addComment(longText.actor, WorkflowFixture.REPORT, new AddComment(8, "🧭".repeat(4001))), "VALIDATION_FAILED", 400);
    }
    @Test void rejectedMutationsLeaveAggregateAndChildrenUnchanged() {
        var f = new WorkflowFixture(); error(() -> f.service.updatePriority(f.actor, WorkflowFixture.REPORT, new PriorityChange(7, Priority.HIGH)), "STALE_VERSION", 409);
        assertSame(f.initial, f.report); assertEquals(List.of(f.retained, f.pending), f.proposals); assertEquals(List.of(f.priorEvent), f.history);
        var target = new WorkflowFixture(); target.report = withStatus(target.report, Status.SUBMITTED, null);
        error(() -> target.service.assign(target.actor, WorkflowFixture.REPORT, new AssignmentChange(8, WorkflowFixture.INACTIVE, null)), "VALIDATION_FAILED", 400);
        assertEquals(List.of(target.priorEvent), target.history); assertSame(target.initial, target.report);
    }
    @Test void failureAppendingHistoryRollsBackReportProposalAndComment() {
        var close = new WorkflowFixture(); close.failAppendHistory = true;
        assertThrows(IllegalStateException.class, () -> close.service.close(close.actor, WorkflowFixture.REPORT, new CloseReport(8, WorkflowFixture.PROPOSAL, 3, true)));
        assertSame(close.initial, close.report); assertEquals(List.of(close.retained, close.pending), close.proposals); assertEquals(List.of(close.priorEvent), close.history);
        var comment = new WorkflowFixture(); comment.failAppendHistory = true;
        assertThrows(IllegalStateException.class, () -> comment.service.addComment(comment.actor, WorkflowFixture.REPORT, new AddComment(8, "comment")));
        assertSame(comment.initial, comment.report); assertTrue(comment.comments.isEmpty()); assertEquals(List.of(comment.priorEvent), comment.history);
    }
    private static ReportCore withStatus(ReportCore r, Status status, UUID assignee) {
        return new ReportCore(r.id(), r.title(), r.description(), r.reporterId(), assignee, status, r.priority(), r.version(),
                r.createdAt(), r.updatedAt(), r.reproductionSteps(), r.expectedBehavior(), r.actualBehavior(), r.environment(), r.currentProposalId());
    }
    private static void error(org.junit.jupiter.api.function.Executable action, String code, int status) {
        var ex = assertThrows(MaintainerService.ServiceException.class, action); assertEquals(code, ex.code()); assertEquals(status, ex.statusCode());
    }
}
