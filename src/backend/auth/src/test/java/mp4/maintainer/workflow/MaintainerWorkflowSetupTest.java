package mp4.maintainer.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static mp4.maintainer.workflow.MaintainerContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Setup control: the workflow API and DTOs compile before behavior Red is designed. */
class MaintainerWorkflowSetupTest {
    @Test
    void serviceApiAndDtoShapesAreExecutableWithoutCallingRepositoryDuringConstruction() throws Exception {
        var calls = new AtomicInteger();
        Repository repository = new Repository() {
            public ReportPage listReports(Actor actor, QueryFilters filters) { calls.incrementAndGet(); return new ReportPage(List.of(), 0); }
            public ReportDetail findReportDetail(Actor actor, UUID id) { calls.incrementAndGet(); return null; }
            public List<Person> listActiveDevelopers(Actor actor) { calls.incrementAndGet(); return List.of(); }
            public <T> T inTransaction(TransactionWork<T> work) { calls.incrementAndGet(); return null; }
        };
        var service = new MaintainerService(repository);
        var reportId = UUID.randomUUID();

        assertNotNull(service);
        assertEquals(0, calls.get(), "service construction must not consult the repository");

        assertEquals(ReportPage.class, MaintainerService.class
            .getMethod("listReports", Actor.class, QueryFilters.class).getReturnType());
        assertEquals(ReportDetail.class, MaintainerService.class
            .getMethod("reportDetail", Actor.class, UUID.class).getReturnType());
        assertEquals(List.class, MaintainerService.class.getMethod("developers", Actor.class).getReturnType());
        assertEquals(ReportSummary.class, MaintainerService.class
            .getMethod("updatePriority", Actor.class, UUID.class, PriorityChange.class).getReturnType());
        assertEquals(ReportSummary.class, MaintainerService.class
            .getMethod("assign", Actor.class, UUID.class, AssignmentChange.class).getReturnType());
        assertEquals(ReportSummary.class, MaintainerService.class
            .getMethod("returnResolution", Actor.class, UUID.class, ReturnResolution.class).getReturnType());
        assertEquals(ReportSummary.class, MaintainerService.class
            .getMethod("close", Actor.class, UUID.class, CloseReport.class).getReturnType());
        assertEquals(ReportSummary.class, MaintainerService.class
            .getMethod("reopen", Actor.class, UUID.class, ReopenReport.class).getReturnType());
        assertEquals(CommentResult.class, MaintainerService.class
            .getMethod("addComment", Actor.class, UUID.class, AddComment.class).getReturnType());
        assertEquals(Person.class, Transaction.class.getMethod("person", UUID.class).getReturnType());
        assertEquals(ReportSummary.class,
            Transaction.class.getMethod("projectSummary", ReportCore.class).getReturnType());

        var summary = new ReportSummary(reportId, "Title", Status.ASSIGNED, Priority.MEDIUM,
            new Person(UUID.randomUUID(), "Reporter"), new Person(UUID.randomUUID(), "Developer"),
            0, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        assertEquals(reportId, summary.id());
        assertEquals(50, QueryFilters.defaults().limit());
        assertEquals(0, new PriorityChange(0, Priority.HIGH).expectedVersion());
        assertEquals(MaintainerContracts.MAX_SAFE_VERSION,
            new PriorityChange(MaintainerContracts.MAX_SAFE_VERSION, Priority.HIGH).expectedVersion());
        assertThrows(IllegalArgumentException.class,
            () -> new PriorityChange(MaintainerContracts.MAX_SAFE_VERSION + 1, Priority.HIGH));
        assertEquals("Please revise", new ReturnResolution(0, UUID.randomUUID(), 0, "Please revise").reason());
        assertEquals("Question", new AddComment(0, "Question").text());
        assertArrayEquals(new ProposalStatus[]{ProposalStatus.PENDING, ProposalStatus.SUPERSEDED, ProposalStatus.CLOSED},
            ProposalStatus.values());
        assertFalse(java.util.Arrays.stream(ReportDetail.class.getRecordComponents())
            .anyMatch(component -> component.getName().toLowerCase().contains("draft")
                || component.getName().toLowerCase().contains("private")));
    }

    @Test
    void statusWireLabelsAndStoredValuesAreMappedExplicitly() {
        assertEquals("In progress", Status.IN_PROGRESS.wireLabel());
        assertEquals(Status.RESOLUTION_PROPOSED, Status.fromStorage("RESOLUTION_PROPOSED"));
        assertThrows(IllegalArgumentException.class, () -> Status.fromStorage("Resolution proposed"));
    }

}
