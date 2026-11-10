package mp4.maintainer.summary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import static mp4.maintainer.summary.SummaryContracts.*;
import static mp4.maintainer.summary.SummaryFixture.*;

/** Frozen controlled-provider comparison; never a claim about live SoC quality or latency. */
public final class ReuseExperiment {
    private static final Filters ANY = new Filters(null, null, null);

    public static void main(String[] args) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", 1);
        result.put("provider", "controlled in-memory fixture; no SoC calls");
        result.put("reuse", condition(false));
        result.put("regeneration", condition(true));
        var fixture = fixture();
        var service = fixture.service();
        service.generate(fixture.actor, ANY, false);
        fixture.snapshot.set(snapshot(List.of(changed(report(1), 2, report(1).assignmentId()))));
        service.generate(fixture.actor, ANY, false);
        require(fixture.gatewayCalls.get() == 2, "source version invalidation");
        fixture.snapshot.set(snapshot(List.of(changed(report(1), 2, id(999)))));
        service.generate(fixture.actor, ANY, false);
        require(fixture.gatewayCalls.get() == 3, "assignment invalidation");
        fixture.snapshot.set(snapshot(List.of(changed(report(1), 2, id(999)), report(2))));
        service.generate(fixture.actor, ANY, false);
        require(fixture.gatewayCalls.get() == 4, "membership invalidation");
        fixture.model = new ModelIdentity("test-provider", "summary-test", "config-2");
        service.generate(fixture.actor, ANY, false);
        require(fixture.gatewayCalls.get() == 5, "model invalidation");
        fixture.authorized.set(false);
        try {
            service.generate(fixture.actor, ANY, false);
            throw new AssertionError("revoked access must be denied");
        } catch (SummaryFailure denied) {
            require(denied.failureCode() == FailureCode.FORBIDDEN, "authorization classification");
        }
        require(fixture.gatewayCalls.get() == 5, "no extra call after revocation");
        result.put("invalidation", Map.of("source", true, "assignment", true, "membership", true,
                "model", true, "revocation", true, "totalProviderCalls", fixture.gatewayCalls.get()));
        result.put("tokenUsage", "unavailable: controlled provider has no token accounting");
        result.put("latencyLimit", "local elapsed times include JVM warmup; no network-performance conclusion");
        System.out.println("MP4_REUSE_RESULT=" + new ObjectMapper().writeValueAsString(result));
    }

    private static Map<String, Object> condition(boolean regenerate) {
        var fixture = fixture();
        var service = fixture.service();
        long started = System.nanoTime();
        for (int request = 0; request < 10; request++) service.generate(fixture.actor, ANY, regenerate);
        long elapsed = System.nanoTime() - started;
        require(fixture.gatewayCalls.get() == (regenerate ? 10 : 1), "provider call comparison");
        return Map.of("requests", 10, "providerCalls", fixture.gatewayCalls.get(), "elapsedNanos", elapsed);
    }

    private static SummaryFixture fixture() {
        var fixture = new SummaryFixture();
        fixture.mode = ProviderMode.DETERMINISTIC;
        fixture.snapshot.set(snapshot(List.of(report(1))));
        return fixture;
    }

    private static ReportInput changed(ReportInput report, long version, java.util.UUID assignment) {
        return new ReportInput(report.id(), version, report.status(), report.priority(), assignment,
                report.createdAt(), report.updatedAt(), report.title(), report.description(),
                report.reproductionSteps(), report.expectedBehavior(), report.actualBehavior(),
                report.environment(), report.latestUpdates());
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
