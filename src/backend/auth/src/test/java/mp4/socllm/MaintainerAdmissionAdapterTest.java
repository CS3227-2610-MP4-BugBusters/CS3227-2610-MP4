package mp4.socllm;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mp4.maintainer.summary.SummaryContracts.Actor;
import mp4.maintainer.workflow.MaintainerContracts.Role;
import mp4.socllm.SocLlmContracts.Task;
import org.junit.jupiter.api.Test;

class MaintainerAdmissionAdapterTest {
    private static Actor maintainer() {
        return new Actor(UUID.randomUUID(), Role.MAINTAINER, true, 0, "session");
    }

    @Test void TL11_defaultReservationIsPolicySum() {
        AtomicInteger reserved = new AtomicInteger();
        var adapter = new MaintainerAdmissionAdapter((r, a, t, tokens, lease) -> { reserved.set(tokens); return null; });
        adapter.acquire(maintainer(), null, Duration.ofSeconds(60));
        assertEquals(34_816, reserved.get());
    }

    @Test void TL11_reservationFollowsInjectedMaintainerPolicy() {
        Map<Task, SocLlmTaskPolicy> map = new EnumMap<>(Task.class);
        for (Task t : Task.values()) map.put(t, SocLlmTaskPolicies.defaults().forTask(t));
        map.put(Task.MAINTAINER_QUEUE_SUMMARY,
                new SocLlmTaskPolicy(1_000, 500, 100, Duration.ofSeconds(10), 3, Duration.ofMinutes(5), 1));
        AtomicInteger reserved = new AtomicInteger();
        var adapter = new MaintainerAdmissionAdapter((r, a, t, tokens, lease) -> { reserved.set(tokens); return null; },
                SocLlmTaskPolicies.of(map));
        adapter.acquire(maintainer(), null, Duration.ofSeconds(60));
        assertEquals(1_600, reserved.get());
    }
}
