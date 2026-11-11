package mp4.maintainer.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Shared DTOs and persistence ports for the Maintainer report workflow. */
public final class MaintainerContracts {
    private MaintainerContracts() {}
    public static final long MAX_SAFE_VERSION = 9_007_199_254_740_991L;

    public enum Role { REPORTER, DEVELOPER, MAINTAINER }

    /** Wire labels follow the Maintainer browser contract; storage adapters map these explicitly. */
    public enum Status {
        SUBMITTED("Submitted"), ASSIGNED("Assigned"), IN_PROGRESS("In progress"),
        RESOLUTION_PROPOSED("Resolution proposed"), CLOSED("Closed");

        private final String wireLabel;
        Status(String wireLabel) { this.wireLabel = wireLabel; }
        public String wireLabel() { return wireLabel; }

        public static Status fromStorage(String value) {
            return switch (Objects.requireNonNull(value, "value")) {
                case "SUBMITTED" -> SUBMITTED;
                case "ASSIGNED" -> ASSIGNED;
                case "IN_PROGRESS" -> IN_PROGRESS;
                case "RESOLUTION_PROPOSED" -> RESOLUTION_PROPOSED;
                case "CLOSED" -> CLOSED;
                default -> throw new IllegalArgumentException("Unknown stored report status");
            };
        }
    }

    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
    /** Stored proposal lifecycle labels used by the Developer proposal adapter. */
    public enum ProposalStatus { PENDING, SUPERSEDED, CLOSED }
    public enum NotificationType { ASSIGNED, UNASSIGNED, RESOLUTION_RETURNED, REOPENED }

    /** Trusted server-side identity. No session token or credential is carried in this contract. */
    public record Actor(UUID accountId, Role role, boolean active, long generation) {
        public Actor {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(role, "role");
            if (generation < 0) throw new IllegalArgumentException("generation must be nonnegative");
        }
    }

    /** Public account identity shape; only id and displayName cross a Maintainer response boundary. */
    public record Person(UUID id, String displayName) {
        public Person {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(displayName, "displayName");
        }
    }

    /** Internal row reread under the account lock. This type is never a response DTO. */
    public record AccountEligibility(UUID id, Role role, boolean active, long generation) {
        public AccountEligibility {
            Objects.requireNonNull(id); Objects.requireNonNull(role);
            if (generation < 0) throw new IllegalArgumentException("generation must be nonnegative");
        }
    }

    /** Shared report fields and metadata; never includes Developer private notes or draft checklist data. */
    public record ReportCore(UUID id, String title, String description, UUID reporterId,
                             UUID assigneeId, Status status, Priority priority, long version,
                             Instant createdAt, Instant updatedAt, String reproductionSteps,
                             String expectedBehavior, String actualBehavior, String environment,
                             UUID currentProposalId) {
        public ReportCore {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(title, "title");
            Objects.requireNonNull(description, "description"); Objects.requireNonNull(reporterId, "reporterId");
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
            requireSafeVersion(version);
        }
    }

    public record QueryFilters(Status status, Priority priority, UUID assigneeId, int limit, int offset) {
        public QueryFilters {
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
            if (offset < 0) throw new IllegalArgumentException("offset must be nonnegative");
        }
        public static QueryFilters defaults() { return new QueryFilters(null, null, null, 50, 0); }
    }

    public record ReportSummary(UUID id, String title, Status status, Priority priority,
                                Person reporter, Person assignee, long version,
                                Instant createdAt, Instant updatedAt) {
        public ReportSummary {
            Objects.requireNonNull(id); Objects.requireNonNull(title); Objects.requireNonNull(status);
            Objects.requireNonNull(priority); Objects.requireNonNull(reporter);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
            requireSafeVersion(version);
        }
    }

    public record ReportPage(List<ReportSummary> items, long total) {
        public ReportPage { items = List.copyOf(items); requireSafeVersion(total); }
    }
    public record ChecklistItem(UUID id, String text, boolean completed) {
        public ChecklistItem { Objects.requireNonNull(id); Objects.requireNonNull(text); }
    }
    public record Evidence(UUID id, Person author, String text, Instant createdAt) {
        public Evidence { Objects.requireNonNull(id); Objects.requireNonNull(author); Objects.requireNonNull(text); Objects.requireNonNull(createdAt); }
    }
    public record Comment(UUID id, Person author, String text, Instant createdAt) {
        public Comment { Objects.requireNonNull(id); Objects.requireNonNull(author); Objects.requireNonNull(text); Objects.requireNonNull(createdAt); }
    }
    public record Proposal(UUID id, long version, ProposalStatus status, String text,
                           Person author, Instant createdAt) {
        public Proposal {
            Objects.requireNonNull(id); Objects.requireNonNull(status); Objects.requireNonNull(text);
            Objects.requireNonNull(author); Objects.requireNonNull(createdAt);
            requireSafeVersion(version);
        }
    }
    public record HistoryEvent(UUID id, String type, String text, Person actor, Instant createdAt) {
        public HistoryEvent { Objects.requireNonNull(id); Objects.requireNonNull(type); Objects.requireNonNull(text); Objects.requireNonNull(actor); Objects.requireNonNull(createdAt); }
    }
    /** Detail exposes shared evidence only. Private notes, draft steps and draft revision have no representable slot. */
    public record ReportDetail(ReportSummary report, String description, String reproductionSteps,
                               String expectedBehavior, String actualBehavior, String environment,
                               List<Evidence> notes, List<ChecklistItem> checklist,
                               List<Evidence> responses, List<Comment> comments,
                               Proposal currentProposal, List<Proposal> proposalHistory,
                               List<HistoryEvent> history) {
        public ReportDetail {
            Objects.requireNonNull(report); Objects.requireNonNull(description);
            notes = List.copyOf(notes); checklist = List.copyOf(checklist); responses = List.copyOf(responses);
            comments = List.copyOf(comments); proposalHistory = List.copyOf(proposalHistory); history = List.copyOf(history);
        }
    }
    public record CommentResult(Comment comment, long reportVersion) {
        public CommentResult { Objects.requireNonNull(comment); requireSafeVersion(reportVersion); }
    }

    public record PriorityChange(long expectedVersion, Priority priority) {
        public PriorityChange { requireVersion(expectedVersion); Objects.requireNonNull(priority); }
    }
    public record AssignmentChange(long expectedVersion, UUID developerId, String reason) {
        public AssignmentChange { requireVersion(expectedVersion); Objects.requireNonNull(developerId); }
    }
    public record ReturnResolution(long expectedVersion, UUID proposalId, long proposalVersion, String reason) {
        public ReturnResolution { requireVersion(expectedVersion); Objects.requireNonNull(proposalId); requireVersion(proposalVersion); Objects.requireNonNull(reason); }
    }
    public record CloseReport(long expectedVersion, UUID proposalId, long proposalVersion, boolean reviewed) {
        public CloseReport { requireVersion(expectedVersion); Objects.requireNonNull(proposalId); requireVersion(proposalVersion); }
    }
    public record ReopenReport(long expectedVersion, String reason) {
        public ReopenReport { requireVersion(expectedVersion); Objects.requireNonNull(reason); }
    }
    public record AddComment(long expectedVersion, String text) {
        public AddComment { requireVersion(expectedVersion); Objects.requireNonNull(text); }
    }

    private static void requireVersion(long value) {
        requireSafeVersion(value);
    }

    private static void requireSafeVersion(long value) {
        if (value < 0 || value > MAX_SAFE_VERSION)
            throw new IllegalArgumentException("version must be a nonnegative JavaScript-safe integer");
    }

    /**
     * Repository boundary shared with the existing report store. Implementations must not create a
     * Maintainer-owned report copy. Reads return authorized safe projections. A unit of work commits
     * every change/history/proposal update together, or rolls all of them back when work throws.
     */
    public interface Repository {
        /** Each read rechecks active MAINTAINER role and the current session generation before exposing data. */
        ReportPage listReports(Actor actor, QueryFilters filters);
        /** Authorization and safe shared-only detail projection are evaluated on the same consistent read. */
        ReportDetail findReportDetail(Actor actor, UUID reportId);
        /** Rechecks the caller before returning only active Developer summaries. */
        List<Person> listActiveDevelopers(Actor actor);
        <T> T inTransaction(TransactionWork<T> work);
    }

    @FunctionalInterface public interface TransactionWork<T> { T apply(Transaction transaction); }

    /** Transaction methods are deliberately ordered: account eligibility, report, then child rows. */
    public interface Transaction {
        /** Locks and rereads current actor plus optional target accounts in UUID order. */
        List<AccountEligibility> lockAccountsInIdOrder(Actor expectedActor, List<UUID> accountIds);
        /** Locks the shared reports row after account locks. */
        ReportCore lockReport(UUID reportId);
        /** Loads report children only after lockReport has acquired the shared report lock. */
        AggregateChildren loadChildren(UUID reportId);
        /** Resolves display-only account metadata inside this unit of work without acquiring account locks. */
        Person person(UUID accountId);
        /** Projects a freshly read report using display metadata in this unit of work; call after lockReport. */
        ReportSummary projectSummary(ReportCore report);
        /** Persists the shared report row; caller appends any matching history/child edits in this unit. */
        void updateReport(ReportCore report);
        void appendHistory(UUID reportId, HistoryEvent event);
        default void createDeveloperNotification(UUID recipientId, UUID reportId, NotificationType type,
                                                  long reportVersion) { }
        void insertComment(UUID reportId, Comment comment);
        void updateProposal(UUID reportId, Proposal proposal);
    }

    /** Proposal, notes, responses and published checklist only; never contains private draft material. */
    public record AggregateChildren(List<Evidence> notes, List<ChecklistItem> sharedChecklist,
                                    List<Evidence> responses, List<Comment> comments,
                                    List<Proposal> proposals, List<HistoryEvent> history) {
        public AggregateChildren {
            notes = List.copyOf(notes); sharedChecklist = List.copyOf(sharedChecklist);
            responses = List.copyOf(responses); comments = List.copyOf(comments);
            proposals = List.copyOf(proposals); history = List.copyOf(history);
        }
    }
}
