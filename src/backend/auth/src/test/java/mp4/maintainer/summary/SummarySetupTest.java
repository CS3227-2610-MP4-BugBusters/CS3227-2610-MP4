package mp4.maintainer.summary;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mp4.maintainer.workflow.MaintainerContracts.Role;

import static mp4.maintainer.summary.SummaryContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Setup control: the service/API and dependency seams compile before summary behavior is added. */
class SummarySetupTest {
    @Test
    void constructorAndImmutableBoundaryNeedNoPortCalls() {
        var calls = new AtomicInteger();
        var actor = new Actor(UUID.randomUUID(), Role.MAINTAINER, true, 2, UUID.randomUUID().toString());
        var service = new QueueSummaryService(
            ignored -> { calls.incrementAndGet(); return true; },
            (ignored, filters, maximum) -> { calls.incrementAndGet(); return new Snapshot(Instant.EPOCH, 0, java.util.List.of()); },
            new SummaryGateway() {
                public ModelIdentity modelIdentity() { calls.incrementAndGet(); return new ModelIdentity("fake", "test", "1"); }
                public ProviderMode providerMode() { calls.incrementAndGet(); return ProviderMode.DETERMINISTIC; }
                public GatewayResponse generate(GatewayRequest request) { calls.incrementAndGet(); throw new AssertionError("gateway called"); }
            },
            (ignored, model, lease) -> { calls.incrementAndGet(); throw new AssertionError("budget acquired"); },
            new SummaryCache() {
                public SummaryResult find(CacheKey key, Instant now) { calls.incrementAndGet(); return null; }
                public void store(CacheKey key, SummaryResult result, Instant expiry) { calls.incrementAndGet(); }
            },
            Limits.proposedDefaults(), Clock.systemUTC());

        assertNotNull(service, "constructor-injected service boundary must be available");
        assertEquals(Role.MAINTAINER, actor.role());
        assertEquals(2, actor.generation());
        assertEquals(0, calls.get(), "constructing the service must not authorize, read, call a gateway or touch state");
        assertTrue(actor.toString().contains("<redacted>"), "internal session identity must not be logged");
        assertEquals(Duration.ofSeconds(30), Limits.proposedDefaults().providerDeadline());
    }
}
