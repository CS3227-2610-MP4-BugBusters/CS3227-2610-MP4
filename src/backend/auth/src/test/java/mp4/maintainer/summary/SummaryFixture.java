package mp4.maintainer.summary;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mp4.maintainer.workflow.MaintainerContracts.Priority;
import mp4.maintainer.workflow.MaintainerContracts.Role;
import mp4.maintainer.workflow.MaintainerContracts.Status;

import static mp4.maintainer.summary.SummaryContracts.*;

/** Deterministic in-memory ports for isolated QueueSummaryService behavior tests. */
final class SummaryFixture {
    static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    static final ModelIdentity MODEL = new ModelIdentity("test-provider", "summary-test", "config-1");
    static final String EMPTY_JSON = "{\"overview\":[],\"blockers\":[],\"missingInformation\":[]}";
    static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    ModelIdentity model = MODEL;

    final Actor actor = new Actor(ACCOUNT, Role.MAINTAINER, true, 4, "session-A");
    final AtomicReference<Boolean> authorized = new AtomicReference<>(true);
    final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(NOW, 1, List.of()));
    final AtomicReference<String> responseJson = new AtomicReference<>(EMPTY_JSON);
    final AtomicReference<SummaryFailure> gatewayFailure = new AtomicReference<>();
    Runnable afterGatewayResponse = () -> {};
    final AtomicInteger authorizationCalls = new AtomicInteger();
    final AtomicInteger snapshotCalls = new AtomicInteger();
    final AtomicInteger gatewayCalls = new AtomicInteger();
    final AtomicInteger admissionCalls = new AtomicInteger();
    final AtomicInteger cacheFindCalls = new AtomicInteger();
    final AtomicInteger cacheStoreCalls = new AtomicInteger();
    final List<GatewayRequest> requests = new ArrayList<>();
    final List<CacheKey> keys = new ArrayList<>();
    final List<SummaryResult> storedResults = new ArrayList<>();
    final List<Instant> expiries = new ArrayList<>();
    final Map<CacheKey, SummaryResult> cacheEntries = new HashMap<>();
    SummaryFailure admissionFailure;
    ProviderMode mode = ProviderMode.LIVE;
    boolean leaseClosed;
    boolean leaseRenewed;
    final SummaryGateway gateway = new SummaryGateway() {
        @Override public ModelIdentity modelIdentity() { return model; }
        @Override public ProviderMode providerMode() { return mode; }
        @Override public GatewayResponse generate(GatewayRequest request) {
            gatewayCalls.incrementAndGet();
            requests.add(request);
            SummaryFailure failure = gatewayFailure.get();
            if (failure != null) throw failure;
            GatewayResponse response = new GatewayResponse(responseJson.get(), model, mode);
            afterGatewayResponse.run();
            return response;
        }
    };
    final SummaryCache cache = new SummaryCache() {
        @Override public SummaryResult find(CacheKey key, Instant now) {
            cacheFindCalls.incrementAndGet();
            keys.add(key);
            return cacheEntries.get(key);
        }
        @Override public void store(CacheKey key, SummaryResult result, Instant expiresAt) {
            cacheStoreCalls.incrementAndGet();
            keys.add(key);
            storedResults.add(result);
            expiries.add(expiresAt);
            cacheEntries.put(key, result);
        }
    };

    QueueSummaryService service() { return service(true, Limits.proposedDefaults()); }

    QueueSummaryService service(boolean allowDeterministic, Limits limits) {
        return new QueueSummaryService(
            who -> { authorizationCalls.incrementAndGet(); return authorized.get(); },
            (who, filters, max) -> { snapshotCalls.incrementAndGet(); return snapshot.get(); },
            gateway,
            (who, model, leaseDuration) -> {
                admissionCalls.incrementAndGet();
                if (admissionFailure != null) throw admissionFailure;
                return new AdmissionLease() {
                    @Override public void renewUntilWorkerTerminates() { leaseRenewed = true; }
                    @Override public void close() { leaseClosed = true; }
                };
            },
            cache, limits, Clock.fixed(NOW, java.time.ZoneOffset.UTC), allowDeterministic);
    }

    static ReportInput report(int n, Priority priority, Instant updatedAt, String title) {
        return new ReportInput(id(n), n, Status.IN_PROGRESS, priority, id(9000 + n),
            NOW.minusSeconds(3600), updatedAt, title, "Description " + n, "Repro " + n,
            "Expected " + n, "Actual " + n, "Environment " + n,
            List.of(new UpdateInput(updatedAt.minusSeconds(2), "older update"),
                new UpdateInput(updatedAt.minusSeconds(1), "middle update"),
                new UpdateInput(updatedAt, "latest update")));
    }

    static ReportInput report(int n) {
        return report(n, Priority.MEDIUM, NOW.minusSeconds(n), "Report " + n);
    }

    static UUID id(int n) { return new UUID(0, n); }

    static Snapshot snapshot(List<ReportInput> reports) { return new Snapshot(NOW, 7, reports); }

    static String jsonFor(UUID id) {
        return "{\"overview\":[{\"text\":\"finding\",\"reportIds\":[\"" + id + "\"]}],"
            + "\"blockers\":[],\"missingInformation\":[]}";
    }
}
