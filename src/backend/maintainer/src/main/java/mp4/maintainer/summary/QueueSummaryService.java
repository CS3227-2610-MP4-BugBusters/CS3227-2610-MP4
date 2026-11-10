package mp4.maintainer.summary;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static mp4.maintainer.summary.SummaryContracts.*;

/** Read-only, authorization-checked queue summary orchestration. */
public final class QueueSummaryService {
    private static final AtomicLong WORKER_SEQUENCE = new AtomicLong();
    private final CurrentAuthorization authorization;
    private final SnapshotReader snapshots;
    private final SummaryGateway gateway;
    private final BudgetAndAdmission admission;
    private final SummaryCache cache;
    private final Limits limits;
    private final Clock clock;
    private final boolean allowDeterministic;

    public QueueSummaryService(CurrentAuthorization authorization, SnapshotReader snapshots,
                               SummaryGateway gateway, BudgetAndAdmission admission,
                               SummaryCache cache, Limits limits, Clock clock) {
        this(authorization, snapshots, gateway, admission, cache, limits, clock, false);
    }

    /** Deterministic gateways require explicit development/test opt-in; production defaults deny. */
    public QueueSummaryService(CurrentAuthorization authorization, SnapshotReader snapshots,
                               SummaryGateway gateway, BudgetAndAdmission admission,
                               SummaryCache cache, Limits limits, Clock clock,
                               boolean allowDeterministic) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.allowDeterministic = allowDeterministic;
    }

    public SummaryResult generate(Actor actor, Filters filters, boolean regenerate) {
        validateRequest(actor, filters);
        requireCurrent(actor);
        Snapshot snapshot = readSnapshot(actor, filters);
        validateSnapshot(snapshot, filters);
        QueueSummaryEngine.Selection selection = QueueSummaryEngine.select(snapshot.reports(), limits);
        if (selection.included().isEmpty()) {
            requireCurrent(actor);
            return QueueSummaryEngine.result(snapshot.capturedAt(), filters, selection,
                new Findings(List.of(), List.of(), List.of()), ProviderMode.NONE);
        }

        ModelIdentity model = safePort(gateway::modelIdentity);
        ProviderMode mode = safePort(gateway::providerMode);
        if (model == null || mode == null || mode == ProviderMode.NONE) throw unavailable();
        if (mode == ProviderMode.DETERMINISTIC && !allowDeterministic) throw unavailable();
        CacheKey key = cacheKey(actor, filters, snapshot, model);
        if (!regenerate) {
            CacheKey lookupKey = key;
            SummaryResult candidate = safePort(() -> cache.find(lookupKey, clock.instant()));
            if (candidate != null && candidate.providerMode() == ProviderMode.DETERMINISTIC && !allowDeterministic) {
                throw unavailable();
            }
            if (candidate != null && candidate.providerMode() == mode
                && QueueSummaryEngine.PROMPT_VERSION.equals(candidate.promptVersion())
                && QueueSummaryEngine.validCachedResult(candidate, filters, selection, clock.instant(),
                    limits.resultRetention(), limits.maximumOutputBytes())) {
                requireCurrent(actor);
                Snapshot current = readSnapshot(actor, filters);
                requireCurrent(actor);
                validateSnapshot(current, filters);
                if (QueueSummaryEngine.snapshotMatches(snapshot, current, filters)) {
                    requireCurrent(actor);
                    if (QueueSummaryEngine.validCachedResult(candidate, filters, selection, clock.instant(),
                        limits.resultRetention(), limits.maximumOutputBytes())) return candidate;
                }
                // Inputs changed while checking the cache. Continue against the complete fresh snapshot.
                snapshot = current;
                selection = QueueSummaryEngine.select(snapshot.reports(), limits);
                key = cacheKey(actor, filters, snapshot, model);
                if (selection.included().isEmpty()) {
                    requireCurrent(actor);
                    return QueueSummaryEngine.result(snapshot.capturedAt(), filters, selection,
                        new Findings(List.of(), List.of(), List.of()), ProviderMode.NONE);
                }
            }
        }

        AdmissionLease lease = safePort(() -> admission.acquire(actor, model, limits.minimumWorkerLease()));
        if (lease == null) throw unavailable();
        GatewayResponse response = invokeBounded(lease, QueueSummaryEngine.request(selection, limits));
        requireCurrent(actor);
        Snapshot deliveredSnapshot = readSnapshot(actor, filters);
        requireCurrent(actor);
        validateSnapshot(deliveredSnapshot, filters);
        if (!QueueSummaryEngine.snapshotMatches(snapshot, deliveredSnapshot, filters)) {
            throw new SummaryFailure(FailureCode.AI_STALE_SOURCE);
        }
        if (!model.equals(response.model()) || response.providerMode() != mode) throw unavailable();
        Set<UUID> includedIds = selection.included().stream().map(IncludedReport::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Findings findings = QueueSummaryEngine.parseAndValidate(response.json(), limits.maximumOutputBytes(), includedIds);
        SummaryResult result = QueueSummaryEngine.result(snapshot.capturedAt(), filters, selection, findings, mode);
        Instant expiresAt = clock.instant().plus(limits.resultRetention());
        CacheKey storeKey = key;
        safePort(() -> { cache.store(storeKey, result, expiresAt); return null; });
        requireCurrent(actor);
        return result;
    }

    private void validateRequest(Actor actor, Filters filters) {
        if (actor == null || filters == null) throw new SummaryFailure(FailureCode.VALIDATION_FAILED);
        if (!actor.active() || actor.role() != mp4.maintainer.workflow.MaintainerContracts.Role.MAINTAINER) {
            throw new SummaryFailure(FailureCode.FORBIDDEN);
        }
        if (filters.status() == mp4.maintainer.workflow.MaintainerContracts.Status.CLOSED) {
            throw new SummaryFailure(FailureCode.VALIDATION_FAILED);
        }
    }

    private void requireCurrent(Actor actor) {
        Boolean current = safePort(() -> authorization.isCurrentMaintainer(actor));
        if (!Boolean.TRUE.equals(current)) throw new SummaryFailure(FailureCode.FORBIDDEN);
    }

    private Snapshot readSnapshot(Actor actor, Filters filters) {
        Snapshot snapshot = safePort(() -> snapshots.read(actor, filters, limits.maximumQueueMembers()));
        if (snapshot == null) throw unavailable();
        return snapshot;
    }

    private void validateSnapshot(Snapshot snapshot, Filters filters) {
        if (snapshot.reports().size() > limits.maximumQueueMembers()) {
            throw new SummaryFailure(FailureCode.QUEUE_TOO_LARGE);
        }
        Set<UUID> ids = new java.util.HashSet<>();
        for (var row : snapshot.reports()) {
            if (row.status() == null || row.priority() == null || row.id() == null
                || row.version() > mp4.maintainer.workflow.MaintainerContracts.MAX_SAFE_VERSION
                || !ids.add(row.id()) || !QueueSummaryEngine.matchesFilters(row, filters)) {
                throw new SummaryFailure(FailureCode.VALIDATION_FAILED);
            }
        }
    }

    private CacheKey cacheKey(Actor actor, Filters filters, Snapshot snapshot, ModelIdentity model) {
        String digest = QueueSummaryEngine.snapshotDigest(snapshot);
        return new CacheKey(actor.accountId(), actor.sessionIdentity(), actor.generation(), filters,
            snapshot.membershipVersion(), digest,
            QueueSummaryEngine.PROMPT_VERSION + ":" + model.configurationVersion(),
            QueueSummaryEngine.CONTEXT_POLICY_VERSION + ":" + limits.maximumQueueMembers() + ":"
                + limits.maximumContextReports() + ":" + limits.maximumContextBytes() + ":"
                + limits.maximumOutputBytes() + ":field-caps-v1", model);
    }

    private GatewayResponse invokeBounded(AdmissionLease lease, GatewayRequest request) {
        // The caller and worker coordinate lease ownership through one state transition. A
        // cancelled task that has not started must not race with the caller closing its lease.
        final int notStarted = 0, running = 1, cancelledBeforeStart = 2, finished = 3;
        java.util.concurrent.atomic.AtomicInteger workerState = new java.util.concurrent.atomic.AtomicInteger(notStarted);
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "maintainer-summary-worker-" + WORKER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS,
            new SynchronousQueue<>(), threads, new ThreadPoolExecutor.AbortPolicy());
        Future<GatewayResponse> future;
        try {
            future = executor.submit(() -> {
                if (!workerState.compareAndSet(notStarted, running)) return null;
                try {
                    try { lease.renewUntilWorkerTerminates(); }
                    catch (RuntimeException failure) { throw unavailable(); }
                    return safePort(() -> gateway.generate(request));
                }
                finally {
                    workerState.set(finished);
                    try { lease.close(); }
                    finally { executor.shutdown(); }
                }
            });
        } catch (RuntimeException failure) {
            executor.shutdownNow();
            if (workerState.compareAndSet(notStarted, cancelledBeforeStart)) safeClose(lease);
            if (failure instanceof java.util.concurrent.RejectedExecutionException) throw new SummaryFailure(FailureCode.AI_BUSY);
            throw unavailable();
        }

        long deadlineNanos = Math.max(1L, limits.providerDeadline().toNanos());
        try {
            return future.get(deadlineNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            executor.shutdownNow();
            if (workerState.compareAndSet(notStarted, cancelledBeforeStart)) safeClose(lease);
            else if (workerState.get() == running) {
                try { lease.renewUntilWorkerTerminates(); } catch (RuntimeException ignored) { /* keep timeout classification */ }
            }
            throw new SummaryFailure(FailureCode.AI_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            executor.shutdownNow();
            if (workerState.compareAndSet(notStarted, cancelledBeforeStart)) safeClose(lease);
            else if (workerState.get() == running) {
                try { lease.renewUntilWorkerTerminates(); } catch (RuntimeException ignored) { /* keep timeout classification */ }
            }
            throw new SummaryFailure(FailureCode.AI_TIMEOUT);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SummaryFailure summaryFailure) throw summaryFailure;
            throw unavailable();
        } finally {
            // A running worker owns the lease close. Never close it from the timed-out caller.
            if (future.isDone() && workerState.compareAndSet(notStarted, cancelledBeforeStart)) safeClose(lease);
        }
    }

    private void safeClose(AdmissionLease lease) {
        try { lease.close(); } catch (RuntimeException ignored) { /* keep provider outcome safe */ }
    }

    private static SummaryFailure unavailable() { return new SummaryFailure(FailureCode.AI_UNAVAILABLE); }

    @FunctionalInterface private interface PortCall<T> { T call(); }
    private static <T> T safePort(PortCall<T> call) {
        try { return call.call(); }
        catch (SummaryFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    /** Retained for source compatibility with the earlier setup-only boundary. */
    public static final class NotImplementedException extends SummaryFailure {
        public NotImplementedException() { super(FailureCode.NOT_IMPLEMENTED); }
    }
}
