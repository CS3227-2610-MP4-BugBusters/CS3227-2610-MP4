package mp4.maintainer.workflow;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static mp4.maintainer.workflow.MaintainerContracts.*;

/** Domain rules for Maintainer report reads and lifecycle operations. */
public final class MaintainerService {
    private final Repository repository;
    private final Clock clock;

    public MaintainerService(Repository repository) {
        this(repository, Clock.systemUTC());
    }

    /** Clock injection keeps event timestamps deterministic in service-level checks. */
    public MaintainerService(Repository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReportPage listReports(Actor actor, QueryFilters filters) {
        requireSnapshotMaintainer(actor);
        if (filters == null) throw validation();
        try {
            ReportPage page = repository.listReports(actor, filters);
            if (page == null) throw unavailable();
            return page;
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    public ReportDetail reportDetail(Actor actor, UUID reportId) {
        requireSnapshotMaintainer(actor);
        if (reportId == null) throw validation();
        try {
            ReportDetail detail = repository.findReportDetail(actor, reportId);
            if (detail == null) throw notFound();
            return detail;
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    public List<Person> developers(Actor actor) {
        requireSnapshotMaintainer(actor);
        try {
            List<Person> people = repository.listActiveDevelopers(actor);
            if (people == null) throw unavailable();
            return List.copyOf(people);
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    public ReportSummary updatePriority(Actor actor, UUID reportId, PriorityChange change) {
        requireSnapshotMaintainer(actor); require(reportId, change);
        return mutate(actor, reportId, List.of(), change.expectedVersion(), (tx, report, children) -> {
            ReportCore changed = next(report, report.status(), change.priority(), report.assigneeId(), report.currentProposalId());
            tx.updateReport(changed);
            history(tx, reportId, "priority_changed", "Priority changed to " + change.priority().name(), actor);
            return tx.projectSummary(changed);
        });
    }

    public ReportSummary assign(Actor actor, UUID reportId, AssignmentChange change) {
        requireSnapshotMaintainer(actor); require(reportId, change);
        validateOptionalReason(change.reason());
        return mutate(actor, reportId, List.of(change.developerId()), change.expectedVersion(), (tx, report, children) -> {
            boolean initialAssignment = report.status() == Status.SUBMITTED;
            boolean reassignment = report.status() == Status.ASSIGNED || report.status() == Status.IN_PROGRESS;
            if (!initialAssignment && !reassignment) throw invalidState();
            if (reassignment && Objects.equals(report.assigneeId(), change.developerId())) throw invalidState();
            if (reassignment) requireReason(change.reason());
            ReportCore changed = next(report, Status.ASSIGNED, report.priority(), change.developerId(), report.currentProposalId());
            tx.updateReport(changed);
            if (reassignment && report.assigneeId() != null)
                tx.createDeveloperNotification(report.assigneeId(), reportId, NotificationType.UNASSIGNED, changed.version());
            tx.createDeveloperNotification(change.developerId(), reportId, NotificationType.ASSIGNED, changed.version());
            String eventText = initialAssignment
                    ? "Assigned to " + tx.person(change.developerId()).displayName()
                    : "Reassigned to " + tx.person(change.developerId()).displayName() + ": " + cleanText(change.reason());
            history(tx, reportId, initialAssignment ? "assigned" : "reassigned", eventText, actor);
            return tx.projectSummary(changed);
        });
    }

    public ReportSummary returnResolution(Actor actor, UUID reportId, ReturnResolution change) {
        requireSnapshotMaintainer(actor); require(reportId, change); requireReason(change.reason());
        return mutate(actor, reportId, List.of(), change.expectedVersion(), (tx, report, children) -> {
            requireStatus(report, Status.RESOLUTION_PROPOSED);
            Proposal proposal = currentPendingProposal(report, children, change.proposalId(), change.proposalVersion());
            tx.updateProposal(reportId, withProposalStatus(proposal, ProposalStatus.SUPERSEDED));
            ReportCore changed = next(report, Status.IN_PROGRESS, report.priority(), report.assigneeId(), null);
            tx.updateReport(changed);
            tx.createDeveloperNotification(report.assigneeId(), reportId, NotificationType.RESOLUTION_RETURNED,
                    changed.version());
            history(tx, reportId, "resolution_returned", "Resolution returned: " + cleanText(change.reason()), actor);
            return tx.projectSummary(changed);
        });
    }

    public ReportSummary close(Actor actor, UUID reportId, CloseReport change) {
        requireSnapshotMaintainer(actor); require(reportId, change);
        if (!change.reviewed()) throw validation();
        return mutate(actor, reportId, List.of(), change.expectedVersion(), (tx, report, children) -> {
            requireStatus(report, Status.RESOLUTION_PROPOSED);
            Proposal proposal = currentPendingProposal(report, children, change.proposalId(), change.proposalVersion());
            tx.updateProposal(reportId, withProposalStatus(proposal, ProposalStatus.CLOSED));
            ReportCore changed = next(report, Status.CLOSED, report.priority(), report.assigneeId(), report.currentProposalId());
            tx.updateReport(changed);
            history(tx, reportId, "resolution_closed", "Reviewed resolution and closed report", actor);
            return tx.projectSummary(changed);
        });
    }

    public ReportSummary reopen(Actor actor, UUID reportId, ReopenReport change) {
        requireSnapshotMaintainer(actor); require(reportId, change); requireReason(change.reason());
        return mutate(actor, reportId, List.of(), change.expectedVersion(), (tx, report, children) -> {
            requireStatus(report, Status.CLOSED);
            ReportCore changed = next(report, Status.SUBMITTED, report.priority(), null, null);
            tx.updateReport(changed);
            if (report.assigneeId() != null)
                tx.createDeveloperNotification(report.assigneeId(), reportId, NotificationType.REOPENED, changed.version());
            history(tx, reportId, "report_reopened", "Report reopened: " + cleanText(change.reason()), actor);
            return tx.projectSummary(changed);
        });
    }

    public CommentResult addComment(Actor actor, UUID reportId, AddComment comment) {
        requireSnapshotMaintainer(actor); require(reportId, comment);
        String text = validateComment(comment.text());
        return mutate(actor, reportId, List.of(), comment.expectedVersion(), (tx, report, children) -> {
            Person author = tx.person(actor.accountId());
            Instant now = clock.instant();
            Comment saved = new Comment(UUID.randomUUID(), author, text, now);
            tx.insertComment(reportId, saved);
            ReportCore changed = next(report, report.status(), report.priority(), report.assigneeId(), report.currentProposalId());
            tx.updateReport(changed);
            history(tx, reportId, "comment_added", "Maintainer added a comment", author, now);
            tx.projectSummary(changed);
            return new CommentResult(saved, changed.version());
        });
    }

    private <T> T mutate(Actor actor, UUID reportId, List<UUID> targetIds, long expectedVersion,
                         Mutation<T> operation) {
        try {
            return repository.inTransaction(tx -> {
                List<UUID> accountIds = new ArrayList<>();
                accountIds.add(actor.accountId()); accountIds.addAll(targetIds);
                accountIds.sort(Comparator.comparing(UUID::toString));
                List<AccountEligibility> locked = tx.lockAccountsInIdOrder(actor, List.copyOf(accountIds));
                Map<UUID, AccountEligibility> accounts = new HashMap<>();
                if (locked != null) for (AccountEligibility row : locked) if (row != null) accounts.put(row.id(), row);
                requireCurrentActor(actor, accounts.get(actor.accountId()));
                for (UUID targetId : targetIds) requireActiveDeveloper(accounts.get(targetId));

                ReportCore report = tx.lockReport(reportId);
                if (report == null) throw notFound();
                AggregateChildren children = tx.loadChildren(reportId);
                if (children == null) throw unavailable();
                requireExpectedVersion(report.version(), expectedVersion);
                ensureIncrementable(report.version());
                return operation.apply(tx, report, children);
            });
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    private ReportCore next(ReportCore old, Status status, Priority priority, UUID assigneeId, UUID proposalId) {
        Instant now = clock.instant();
        return new ReportCore(old.id(), old.title(), old.description(), old.reporterId(), assigneeId,
                status, priority, old.version() + 1, old.createdAt(), now, old.reproductionSteps(),
                old.expectedBehavior(), old.actualBehavior(), old.environment(), proposalId);
    }

    private void history(Transaction tx, UUID reportId, String type, String text, Actor actor) {
        history(tx, reportId, type, text, tx.person(actor.accountId()), clock.instant());
    }

    private static void history(Transaction tx, UUID reportId, String type, String text, Person actor, Instant now) {
        tx.appendHistory(reportId, new HistoryEvent(UUID.randomUUID(), type, text, actor, now));
    }

    private static Proposal currentPendingProposal(ReportCore report, AggregateChildren children,
                                                   UUID proposalId, long proposalVersion) {
        if (!Objects.equals(report.currentProposalId(), proposalId)) throw staleVersion();
        for (Proposal proposal : children.proposals()) {
            if (proposal.id().equals(proposalId)) {
                if (proposal.version() != proposalVersion || proposal.status() != ProposalStatus.PENDING) throw staleVersion();
                return proposal;
            }
        }
        throw staleVersion();
    }

    private static Proposal withProposalStatus(Proposal p, ProposalStatus status) {
        return new Proposal(p.id(), p.version(), status, p.text(), p.author(), p.createdAt());
    }

    private static void requireActiveDeveloper(AccountEligibility account) {
        if (account == null || account.role() != Role.DEVELOPER || !account.active()) throw validation();
    }

    private static void requireCurrentActor(Actor expected, AccountEligibility current) {
        if (current == null || current.role() != Role.MAINTAINER || !current.active()
                || current.generation() != expected.generation()) throw unauthenticated();
    }

    private static void requireSnapshotMaintainer(Actor actor) {
        if (actor == null || actor.role() != Role.MAINTAINER || !actor.active()) throw forbidden();
    }

    private static void requireExpectedVersion(long actual, long expected) {
        if (actual != expected) throw staleVersion();
    }

    private static void ensureIncrementable(long version) {
        if (version >= MAX_SAFE_VERSION) throw new ServiceException("VERSION_LIMIT", 409, "The report version limit has been reached");
    }

    private static void requireStatus(ReportCore report, Status expected) {
        if (report.status() != expected) throw invalidState();
    }

    private static void require(UUID reportId, Object input) {
        if (reportId == null || input == null) throw validation();
    }

    private static void validateOptionalReason(String reason) {
        if (reason != null) requireReason(reason);
    }

    private static String requireReason(String reason) {
        String clean = cleanText(reason);
        if (clean.codePointCount(0, clean.length()) < 1 || clean.codePointCount(0, clean.length()) > 1000
                || hasMalformedSurrogate(clean)) throw validation();
        for (int offset = 0; offset < clean.length();) {
            int cp = clean.codePointAt(offset);
            if (Character.isISOControl(cp) && cp != '\n' && cp != '\t') throw validation();
            offset += Character.charCount(cp);
        }
        return clean;
    }

    private static String validateComment(String input) {
        String clean = cleanText(input);
        int count = clean.codePointCount(0, clean.length());
        if (count < 1 || count > 4000 || hasMalformedSurrogate(clean)) throw validation();
        for (int i = 0; i < clean.length();) {
            int cp = clean.codePointAt(i);
            if (Character.isISOControl(cp) && cp != '\n' && cp != '\t') throw validation();
            i += Character.charCount(cp);
        }
        return clean;
    }

    private static String cleanText(String text) {
        if (text == null) throw validation();
        return text.strip();
    }

    private static boolean hasMalformedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return true;
            } else if (Character.isLowSurrogate(c)) return true;
        }
        return false;
    }

    private static ServiceException forbidden() { return new ServiceException("FORBIDDEN", 403, "Maintainer access is required"); }
    private static ServiceException unauthenticated() { return new ServiceException("UNAUTHENTICATED", 401, "The current Maintainer session is no longer valid"); }
    private static ServiceException validation() { return new ServiceException("VALIDATION_FAILED", 400, "The supplied workflow data is invalid"); }
    private static ServiceException staleVersion() { return new ServiceException("STALE_VERSION", 409, "The report or proposal has changed"); }
    private static ServiceException invalidState() { return new ServiceException("INVALID_STATE", 409, "The report is not in a state for this operation"); }
    private static ServiceException notFound() { return new ServiceException("NOT_FOUND", 404, "Report not found"); }
    private static ServiceException unavailable() { return new ServiceException("TEMPORARILY_UNAVAILABLE", 503, "Maintainer workflow is temporarily unavailable"); }

    @FunctionalInterface private interface Mutation<T> {
        T apply(Transaction tx, ReportCore report, AggregateChildren children);
    }

    /** Safe service error shape for a future HTTP adapter. */
    public static final class ServiceException extends RuntimeException {
        private final String code;
        private final int statusCode;

        public ServiceException(String code, int statusCode, String message) {
            super(Objects.requireNonNull(message, "message"));
            this.code = Objects.requireNonNull(code, "code");
            if (statusCode < 400 || statusCode > 599) throw new IllegalArgumentException("statusCode must be an error status");
            this.statusCode = statusCode;
        }
        public String code() { return code; }
        public int statusCode() { return statusCode; }
    }
}
