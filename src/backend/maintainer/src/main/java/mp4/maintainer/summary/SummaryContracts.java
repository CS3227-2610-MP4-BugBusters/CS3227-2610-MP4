package mp4.maintainer.summary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import mp4.maintainer.workflow.MaintainerContracts.Priority;
import mp4.maintainer.workflow.MaintainerContracts.Role;
import mp4.maintainer.workflow.MaintainerContracts.Status;

/** Immutable ports and data shapes for the isolated Maintainer queue-summary service. */
public final class SummaryContracts {
    private SummaryContracts() {}

    public enum ProviderMode { NONE, DETERMINISTIC, LIVE }

    /** Trusted session projection. sessionIdentity is internal and must never enter DTOs or logs. */
    public record Actor(UUID accountId, Role role, boolean active, long generation,
                        String sessionIdentity) {
        public Actor {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(sessionIdentity, "sessionIdentity");
            if (sessionIdentity.isBlank()) throw new IllegalArgumentException("sessionIdentity must not be blank");
            if (generation < 0) throw new IllegalArgumentException("generation must be nonnegative");
        }
        @Override public String toString() {
            return "Actor[accountId=" + accountId + ", role=" + role + ", active=" + active
                + ", generation=" + generation + ", sessionIdentity=<redacted>]";
        }
    }

    /** Null filters mean unrestricted for that dimension; Closed is rejected for summaries. */
    public record Filters(Status status, Priority priority, UUID assigneeId) {}

    /** Only explicitly selected, shareable source fields are representable here. Private notes,
     * emails, identities, credentials, checklist data and raw discussion are intentionally absent. */
    public record ReportInput(UUID id, long version, Status status, Priority priority,
                              UUID assignmentId, Instant createdAt, Instant updatedAt,
                              String title, String description, String reproductionSteps,
                              String expectedBehavior, String actualBehavior, String environment,
                              List<UpdateInput> latestUpdates) {
        public ReportInput {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(title, "title");
            latestUpdates = List.copyOf(latestUpdates);
            if (version < 0) throw new IllegalArgumentException("version must be nonnegative");
        }
    }

    public record UpdateInput(Instant updatedAt, String text) {
        public UpdateInput { Objects.requireNonNull(updatedAt, "updatedAt"); Objects.requireNonNull(text, "text"); }
    }

    /** All queue members, including reports later omitted by context bounds. */
    public record Snapshot(Instant capturedAt, long membershipVersion, List<ReportInput> reports) {
        public Snapshot {
            Objects.requireNonNull(capturedAt, "capturedAt");
            if (membershipVersion < 0) throw new IllegalArgumentException("membershipVersion must be nonnegative");
            reports = List.copyOf(reports);
        }
    }

    public record Truncation(String field, int originalCodePoints) {
        public Truncation { Objects.requireNonNull(field, "field"); if (originalCodePoints < 0) throw new IllegalArgumentException(); }
    }
    public record IncludedReport(UUID id, long version, List<String> truncatedFields) {
        public IncludedReport { Objects.requireNonNull(id, "id"); truncatedFields = List.copyOf(truncatedFields); }
    }
    public record ExcludedReport(UUID id, String reason) {
        public ExcludedReport { Objects.requireNonNull(id, "id"); Objects.requireNonNull(reason, "reason"); }
    }
    public record Finding(String text, List<UUID> reportIds) {
        public Finding { Objects.requireNonNull(text, "text"); reportIds = List.copyOf(reportIds); }
    }
    public record Findings(List<Finding> overview, List<Finding> blockers,
                           List<Finding> missingInformation) {
        public Findings {
            overview = List.copyOf(overview); blockers = List.copyOf(blockers);
            missingInformation = List.copyOf(missingInformation);
        }
    }
    public record SummaryResult(Instant capturedAt, Filters filters,
                                List<IncludedReport> included, List<ExcludedReport> excluded,
                                Findings findings, boolean requiresReview, ProviderMode providerMode,
                                String promptVersion) {
        public SummaryResult {
            Objects.requireNonNull(capturedAt, "capturedAt"); Objects.requireNonNull(filters, "filters");
            included = List.copyOf(included); excluded = List.copyOf(excluded);
            Objects.requireNonNull(findings, "findings"); Objects.requireNonNull(providerMode, "providerMode");
            Objects.requireNonNull(promptVersion, "promptVersion");
        }
    }

    public record ModelIdentity(String provider, String model, String configurationVersion) {
        public ModelIdentity {
            Objects.requireNonNull(provider, "provider"); Objects.requireNonNull(model, "model");
            Objects.requireNonNull(configurationVersion, "configurationVersion");
        }
    }
    public record TrustedInstructions(String value) { public TrustedInstructions { Objects.requireNonNull(value); } }
    public record UntrustedReportsJson(String value) { public UntrustedReportsJson { Objects.requireNonNull(value); } }
    public record GatewayRequest(TrustedInstructions instructions, UntrustedReportsJson reports,
                                int outputTokenLimit, Duration deadline) {
        public GatewayRequest {
            Objects.requireNonNull(instructions); Objects.requireNonNull(reports); Objects.requireNonNull(deadline);
        }
    }
    public record GatewayResponse(String json, ModelIdentity model, ProviderMode providerMode) {
        public GatewayResponse {
            Objects.requireNonNull(json); Objects.requireNonNull(model); Objects.requireNonNull(providerMode);
        }
    }

    public interface CurrentAuthorization {
        /** Recheck session generation, current active account and Maintainer role. */
        boolean isCurrentMaintainer(Actor actor);
    }
    public interface SnapshotReader {
        /** Reads a consistent, full selected queue snapshot; includes assignment/version for every member. */
        Snapshot read(Actor actor, Filters filters, int maximumMembers);
    }
    public interface SummaryGateway {
        ModelIdentity modelIdentity();
        ProviderMode providerMode();
        /** Instructions and serialized untrusted report data are separate channels. */
        GatewayResponse generate(GatewayRequest request);
    }
    public interface BudgetAndAdmission {
        /** Durable, shared quota admission. Production adapters must fail closed if unavailable. */
        AdmissionLease acquire(Actor actor, ModelIdentity model, Duration leaseDuration);
    }
    public record CacheKey(UUID accountId, String sessionIdentity, long actorGeneration, Filters filters,
                           long membershipVersion, String completeSnapshotDigest,
                           String promptPolicyVersion, String contextPolicyVersion,
                           ModelIdentity model) {
        public CacheKey {
            Objects.requireNonNull(accountId); Objects.requireNonNull(sessionIdentity); Objects.requireNonNull(filters);
            Objects.requireNonNull(completeSnapshotDigest); Objects.requireNonNull(promptPolicyVersion);
            Objects.requireNonNull(contextPolicyVersion); Objects.requireNonNull(model);
        }
        @Override public String toString() {
            return "CacheKey[accountId=" + accountId + ", sessionIdentity=<redacted>, actorGeneration="
                + actorGeneration + ", filters=" + filters + ", membershipVersion=" + membershipVersion
                + ", completeSnapshotDigest=<redacted>, promptPolicyVersion=" + promptPolicyVersion
                + ", contextPolicyVersion=" + contextPolicyVersion + ", model=" + model + "]";
        }
    }
    public interface SummaryCache {
        /** Account/session scoped transient result lookup; caller still performs a fresh access/snapshot check. */
        SummaryResult find(CacheKey key, Instant now);
        void store(CacheKey key, SummaryResult result, Instant expiresAt);
    }
    public interface AdmissionLease extends AutoCloseable {
        /** Keep protection until the provider worker actually terminates, including after client timeout. */
        void renewUntilWorkerTerminates();
        @Override void close();
    }

    /** These are the proposed application defaults in the Maintainer design, not provider quotas. */
    public record Limits(int maximumQueueMembers, int maximumContextReports, int maximumContextBytes,
                         int maximumOutputBytes, Duration providerDeadline, int startsPerWindow,
                         Duration startWindow, int inFlightPerAccount, Duration resultRetention,
                         int outputTokenLimit, Duration minimumWorkerLease) {
        public static Limits proposedDefaults() {
            return new Limits(1000, 20, 24 * 1024, 32 * 1024, Duration.ofSeconds(30), 3,
                Duration.ofMinutes(5), 1, Duration.ofMinutes(5), 2048, Duration.ofSeconds(60));
        }
        public Limits {
            if (maximumQueueMembers <= 0 || maximumContextReports <= 0 || maximumContextBytes <= 0
                || maximumOutputBytes <= 0 || startsPerWindow <= 0 || inFlightPerAccount <= 0
                || outputTokenLimit <= 0) throw new IllegalArgumentException("limits must be positive");
            Objects.requireNonNull(providerDeadline); Objects.requireNonNull(startWindow);
            Objects.requireNonNull(resultRetention); Objects.requireNonNull(minimumWorkerLease);
        }
    }
}
