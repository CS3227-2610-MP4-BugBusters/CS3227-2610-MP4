package mp4.maintainer.workflow;

import java.time.Instant;
import java.util.*;
import static mp4.maintainer.workflow.MaintainerContracts.*;

/** Deterministic transactional fake: useful for service rules, not DB isolation/race evidence. */
final class WorkflowFixture implements Repository {
    static final UUID MAINTAINER = id(1), DEV1 = id(2), DEV2 = id(3), INACTIVE = id(4), WRONG_ROLE = id(5), REPORT = id(10);
    static final UUID PROPOSAL = id(20), OLD_PROPOSAL = id(21);
    static UUID id(long n) { return new UUID(0, n); }
    final Actor actor = new Actor(MAINTAINER, Role.MAINTAINER, true, 7);
    final ReportCore initial = new ReportCore(REPORT, "Login fails", "Details", id(9), DEV1,
            Status.RESOLUTION_PROPOSED, Priority.MEDIUM, 8, Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z"), "steps", "expected", "actual", "env", PROPOSAL);
    final Person maintainer = new Person(MAINTAINER, "Mira Maintainer");
    final Person dev1 = new Person(DEV1, "Dev One");
    final Person dev2 = new Person(DEV2, "Dev Two");
    final Proposal pending = new Proposal(PROPOSAL, 3, ProposalStatus.PENDING, "Fixed", dev1,
            Instant.parse("2026-01-02T00:00:00Z"));
    final Proposal retained = new Proposal(OLD_PROPOSAL, 2, ProposalStatus.SUPERSEDED, "Prior fix", dev1,
            Instant.parse("2026-01-01T00:00:00Z"));
    final Evidence note = new Evidence(id(32), maintainer, "Shared investigation note", Instant.parse("2026-01-01T12:00:00Z"));
    final ChecklistItem checklistItem = new ChecklistItem(id(31), "shared check", true);
    final Evidence response = new Evidence(id(33), dev1, "Shared response", Instant.parse("2026-01-01T13:00:00Z"));
    final HistoryEvent priorEvent = new HistoryEvent(id(34), "created", "Report created", maintainer, Instant.parse("2026-01-01T00:00:00Z"));
    ReportCore report = initial;
    final List<Proposal> proposals = new ArrayList<>(List.of(retained, pending));
    final List<HistoryEvent> history = new ArrayList<>(List.of(priorEvent));
    final List<Comment> comments = new ArrayList<>();
    final List<Evidence> notes = new ArrayList<>(List.of(note));
    final List<ChecklistItem> checklist = new ArrayList<>(List.of(checklistItem));
    final List<Evidence> responses = new ArrayList<>(List.of(response));
    final List<String> calls = new ArrayList<>();
    boolean failAppendHistory;
    Map<UUID, AccountEligibility> eligibility = Map.of(
            MAINTAINER, new AccountEligibility(MAINTAINER, Role.MAINTAINER, true, 7),
            DEV1, new AccountEligibility(DEV1, Role.DEVELOPER, true, 2),
            DEV2, new AccountEligibility(DEV2, Role.DEVELOPER, true, 4),
            INACTIVE, new AccountEligibility(INACTIVE, Role.DEVELOPER, false, 1),
            WRONG_ROLE, new AccountEligibility(WRONG_ROLE, Role.REPORTER, true, 1));
    public ReportPage listReports(Actor a, QueryFilters f) { calls.add("list"); return new ReportPage(List.of(), 0); }
    public ReportDetail findReportDetail(Actor a, UUID id) {
        calls.add("detail");
        if (!REPORT.equals(id)) return null;
        return new ReportDetail(summary(report), report.description(), report.reproductionSteps(), report.expectedBehavior(),
                report.actualBehavior(), report.environment(), notes, checklist, responses, comments,
                proposals.stream().filter(p -> p.id().equals(report.currentProposalId()) && p.status() == ProposalStatus.PENDING).findFirst().orElse(null),
                proposals, history);
    }
    public List<Person> listActiveDevelopers(Actor a) { calls.add("developers"); return List.of(dev1, dev2); }
    public <T> T inTransaction(TransactionWork<T> work) {
        calls.add("begin"); ReportCore oldReport = report; List<Proposal> oldProposals = new ArrayList<>(proposals);
        List<HistoryEvent> oldHistory = new ArrayList<>(history); List<Comment> oldComments = new ArrayList<>(comments);
        List<Evidence> oldNotes = new ArrayList<>(notes); List<ChecklistItem> oldChecklist = new ArrayList<>(checklist);
        List<Evidence> oldResponses = new ArrayList<>(responses);
        try { return work.apply(new Tx()); } catch (RuntimeException | Error ex) {
            report = oldReport; proposals.clear(); proposals.addAll(oldProposals); history.clear(); history.addAll(oldHistory);
            comments.clear(); comments.addAll(oldComments); notes.clear(); notes.addAll(oldNotes);
            checklist.clear(); checklist.addAll(oldChecklist); responses.clear(); responses.addAll(oldResponses);
            calls.add("rollback"); throw ex;
        }
    }
    final class Tx implements Transaction {
        public List<AccountEligibility> lockAccountsInIdOrder(Actor expected, List<UUID> ids) {
            calls.add("accounts:" + ids);
            List<UUID> sorted = new ArrayList<>(ids); sorted.sort(Comparator.comparing(UUID::toString));
            if (!ids.equals(sorted)) throw new AssertionError("account IDs must be locked in stable UUID order: " + ids);
            return ids.stream().map(eligibility::get).filter(Objects::nonNull).toList();
        }
        public ReportCore lockReport(UUID id) { calls.add("report"); return report; }
        public AggregateChildren loadChildren(UUID id) { calls.add("children"); return new AggregateChildren(notes, checklist, responses, comments, proposals, history); }
        public Person person(UUID id) { calls.add("person"); return id.equals(DEV1) ? dev1 : id.equals(DEV2) ? dev2 : maintainer; }
        public ReportSummary projectSummary(ReportCore r) { calls.add("summary"); return new ReportSummary(r.id(), r.title(), r.status(), r.priority(),
                new Person(r.reporterId(), "Reporter"), r.assigneeId() == null ? null : person(r.assigneeId()), r.version(), r.createdAt(), r.updatedAt()); }
        public void updateReport(ReportCore r) { calls.add("updateReport"); report = r; }
        public void appendHistory(UUID id, HistoryEvent e) { calls.add("history"); if (failAppendHistory) throw new IllegalStateException("injected history failure"); history.add(e); }
        public void insertComment(UUID id, Comment c) { calls.add("comment"); comments.add(c); }
        public void updateProposal(UUID id, Proposal p) { calls.add("proposal"); proposals.removeIf(old -> old.id().equals(p.id())); proposals.add(p); }
    }
    final MaintainerService service = new MaintainerService(this);
    private ReportSummary summary(ReportCore r) {
        return new ReportSummary(r.id(), r.title(), r.status(), r.priority(), new Person(r.reporterId(), "Reporter"),
                r.assigneeId() == null ? null : (r.assigneeId().equals(DEV1) ? dev1 : dev2), r.version(), r.createdAt(), r.updatedAt());
    }
    static void assertLockOrder(List<String> calls) {
        int a = -1; for (int i = 0; i < calls.size(); i++) if (calls.get(i).startsWith("accounts:")) { a = i; break; }
        int r = calls.indexOf("report"), c = calls.indexOf("children");
        if (a < 0 || r <= a || c <= r) throw new AssertionError("every transaction must lock accounts, report, children in order: " + calls);
    }
}
