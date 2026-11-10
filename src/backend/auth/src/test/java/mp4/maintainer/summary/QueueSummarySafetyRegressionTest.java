package mp4.maintainer.summary;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static mp4.maintainer.summary.SummaryContracts.*;
import static mp4.maintainer.summary.SummaryFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Additional regression coverage for untrusted cache-port values. */
class QueueSummarySafetyRegressionTest {
    @Test
    void cachedFindingsWithUnknownReportIdsAreDiscardedAndRegenerated() {
        var fixture = new SummaryFixture();
        fixture.snapshot.set(snapshot(List.of(report(1))));
        fixture.responseJson.set(jsonFor(id(1)));
        SummaryResult forged = new SummaryResult(NOW, new Filters(null, null, null),
            List.of(new IncludedReport(id(1), 1, List.of())), List.of(),
            new Findings(List.of(new Finding("forged", List.of(id(2)))), List.of(), List.of()),
            true, ProviderMode.LIVE, "MNT-SUMMARY-1");
        SummaryCache cache = new SummaryCache() {
            @Override public SummaryResult find(CacheKey key, java.time.Instant now) { return forged; }
            @Override public void store(CacheKey key, SummaryResult result, java.time.Instant expiresAt) {}
        };
        QueueSummaryService service = new QueueSummaryService(
            who -> true,
            (who, filters, maximum) -> fixture.snapshot.get(),
            fixture.gateway,
            (who, model, lease) -> new AdmissionLease() {
                @Override public void renewUntilWorkerTerminates() {}
                @Override public void close() {}
            },
            cache, Limits.proposedDefaults(), Clock.fixed(NOW, java.time.ZoneOffset.UTC), true);

        SummaryResult result = service.generate(fixture.actor, new Filters(null, null, null), false);

        assertEquals(1, fixture.gatewayCalls.get(), "invalid cache data must not be delivered");
        assertEquals("finding", result.findings().overview().get(0).text());
        assertEquals(List.of(id(1)), result.findings().overview().get(0).reportIds());
    }

    @Test
    void cachedResultsOutsideRetentionOrOutputByteLimitAreRejected() {
        var selection = QueueSummaryEngine.select(List.of(report(1)), Limits.proposedDefaults());
        var filters = new Filters(null, null, null);
        var allowedFinding = new Findings(List.of(new Finding("ok", List.of(id(1)))), List.of(), List.of());
        var expired = QueueSummaryEngine.result(NOW.minus(Duration.ofMinutes(6)), filters, selection,
            allowedFinding, ProviderMode.LIVE);
        var oversized = QueueSummaryEngine.result(NOW, filters, selection, allowedFinding, ProviderMode.LIVE);

        assertFalse(QueueSummaryEngine.validCachedResult(expired, filters, selection, NOW,
            Duration.ofMinutes(5), Limits.proposedDefaults().maximumOutputBytes()));
        assertFalse(QueueSummaryEngine.validCachedResult(oversized, filters, selection, NOW,
            Duration.ofMinutes(5), 10));
    }

    @Test
    void cacheEntryThatExpiresDuringFinalSnapshotCheckIsRegenerated() {
        var fixture = new SummaryFixture();
        var filters = new Filters(null, null, null);
        Snapshot snapshot = snapshot(List.of(report(1)));
        var selection = QueueSummaryEngine.select(snapshot.reports(), Limits.proposedDefaults());
        SummaryResult cached = QueueSummaryEngine.result(NOW.minus(Duration.ofMinutes(4).plusSeconds(59)),
            filters, selection, new Findings(List.of(new Finding("cached", List.of(id(1)))), List.of(), List.of()),
            ProviderMode.LIVE);
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger reads = new AtomicInteger();
        SnapshotReader reader = (who, selectedFilters, maximum) -> {
            if (reads.incrementAndGet() == 2) clock.advance(Duration.ofSeconds(2));
            return snapshot;
        };
        fixture.responseJson.set("{\"overview\":[{\"text\":\"fresh\",\"reportIds\":[\""
            + id(1) + "\"]}],\"blockers\":[],\"missingInformation\":[]}");
        SummaryCache cache = cachedValue(cached);
        QueueSummaryService service = new QueueSummaryService(who -> true, reader, fixture.gateway,
            (who, model, lease) -> new AdmissionLease() {
                @Override public void renewUntilWorkerTerminates() {}
                @Override public void close() {}
            }, cache, Limits.proposedDefaults(), clock, true);

        SummaryResult result = service.generate(fixture.actor, filters, false);

        assertEquals("fresh", result.findings().overview().get(0).text());
        assertEquals(1, fixture.gatewayCalls.get(), "expired cache value must trigger budgeted regeneration");
        assertEquals(3, reads.get(), "generation must still validate the source snapshot before delivery");
    }

    @Test
    void revocationDuringInitialEmptySnapshotPreventsEmptyResultDelivery() {
        AtomicBoolean authorized = new AtomicBoolean(true);
        var fixture = new SummaryFixture();
        QueueSummaryService service = service(fixture, who -> authorized.get(),
            (who, filters, maximum) -> {
                authorized.set(false);
                return new Snapshot(NOW, 1, List.of());
            }, emptyCache());

        SummaryFailure failure = assertThrows(SummaryFailure.class,
            () -> service.generate(fixture.actor, new Filters(null, null, null), false));

        assertEquals(FailureCode.FORBIDDEN, failure.failureCode());
        assertEquals(0, fixture.gatewayCalls.get());
    }

    @Test
    void revocationDuringCachedSnapshotPreventsCachedResultDelivery() {
        var fixture = new SummaryFixture();
        Snapshot initial = snapshot(List.of(report(1)));
        var selection = QueueSummaryEngine.select(initial.reports(), Limits.proposedDefaults());
        SummaryResult cached = QueueSummaryEngine.result(NOW, new Filters(null, null, null), selection,
            new Findings(List.of(new Finding("cached", List.of(id(1)))), List.of(), List.of()),
            ProviderMode.LIVE);
        AtomicBoolean authorized = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        QueueSummaryService service = service(fixture, who -> authorized.get(),
            (who, filters, maximum) -> {
                if (reads.incrementAndGet() == 2) authorized.set(false);
                return initial;
            }, cachedValue(cached));

        SummaryFailure failure = assertThrows(SummaryFailure.class,
            () -> service.generate(fixture.actor, new Filters(null, null, null), false));

        assertEquals(FailureCode.FORBIDDEN, failure.failureCode());
        assertEquals(0, fixture.gatewayCalls.get());
    }

    @Test
    void revocationDuringGeneratedSnapshotPreventsProviderResultDelivery() {
        var fixture = new SummaryFixture();
        fixture.responseJson.set(jsonFor(id(1)));
        AtomicBoolean authorized = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        QueueSummaryService service = service(fixture, who -> authorized.get(),
            (who, filters, maximum) -> {
                if (reads.incrementAndGet() == 2) authorized.set(false);
                return snapshot(List.of(report(1)));
            }, emptyCache());

        SummaryFailure failure = assertThrows(SummaryFailure.class,
            () -> service.generate(fixture.actor, new Filters(null, null, null), true));

        assertEquals(FailureCode.FORBIDDEN, failure.failureCode());
        assertEquals(1, fixture.gatewayCalls.get());
    }

    @Test
    void revocationDuringCacheStorePreventsGeneratedResultDelivery() {
        var fixture = new SummaryFixture();
        fixture.responseJson.set(jsonFor(id(1)));
        AtomicBoolean authorized = new AtomicBoolean(true);
        SummaryCache revokingCache = new SummaryCache() {
            @Override public SummaryResult find(CacheKey key, Instant now) { return null; }
            @Override public void store(CacheKey key, SummaryResult result, Instant expiry) {
                authorized.set(false);
            }
        };
        QueueSummaryService service = service(fixture, who -> authorized.get(),
            (who, filters, maximum) -> snapshot(List.of(report(1))), revokingCache);

        SummaryFailure failure = assertThrows(SummaryFailure.class,
            () -> service.generate(fixture.actor, new Filters(null, null, null), true));

        assertEquals(FailureCode.FORBIDDEN, failure.failureCode());
        assertEquals(1, fixture.gatewayCalls.get());
    }

    private static QueueSummaryService service(SummaryFixture fixture, CurrentAuthorization authorization,
                                               SnapshotReader snapshots, SummaryCache cache) {
        return new QueueSummaryService(authorization, snapshots, fixture.gateway,
            (who, model, lease) -> new AdmissionLease() {
                @Override public void renewUntilWorkerTerminates() {}
                @Override public void close() {}
            }, cache, Limits.proposedDefaults(), Clock.fixed(NOW, java.time.ZoneOffset.UTC), true);
    }

    private static SummaryCache emptyCache() { return cachedValue(null); }

    private static SummaryCache cachedValue(SummaryResult value) {
        return new SummaryCache() {
            @Override public SummaryResult find(CacheKey key, Instant now) { return value; }
            @Override public void store(CacheKey key, SummaryResult result, Instant expiry) {}
        };
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        private MutableClock(Instant initial) { current = new AtomicReference<>(initial); }
        private void advance(Duration duration) { current.updateAndGet(now -> now.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
