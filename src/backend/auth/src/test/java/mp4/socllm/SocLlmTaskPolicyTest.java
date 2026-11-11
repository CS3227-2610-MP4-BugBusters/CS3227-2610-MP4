package mp4.socllm;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import mp4.socllm.SocLlmContracts.Task;
import org.junit.jupiter.api.Test;

class SocLlmTaskPolicyTest {
    private static SocLlmTaskPolicy policy(int in, int instr, int out, Duration deadline, int starts, Duration window, int inFlight) {
        return new SocLlmTaskPolicy(in, instr, out, deadline, starts, window, inFlight);
    }

    @Test void TL01_everyTaskHasExactlyOnePolicyWithPositiveValues() {
        SocLlmTaskPolicies policies = SocLlmTaskPolicies.defaults();
        for (Task task : Task.values()) {
            SocLlmTaskPolicy p = policies.forTask(task);
            assertNotNull(p, task.name());
            assertTrue(p.maxInputBytes() > 0 && p.maxInstructionBytes() > 0 && p.maxOutputTokens() > 0
                    && p.startsPerWindow() > 0 && p.maxInFlightPerActor() > 0
                    && p.maxDeadline().compareTo(Duration.ZERO) > 0 && p.window().compareTo(Duration.ZERO) > 0, task.name());
        }
    }

    @Test void TL01_registryMissingATaskFails() {
        Map<Task, SocLlmTaskPolicy> partial = new EnumMap<>(Task.class);
        partial.put(Task.REPORTER_ASSIST, SocLlmTaskPolicies.defaults().forTask(Task.REPORTER_ASSIST));
        assertThrows(IllegalArgumentException.class, () -> SocLlmTaskPolicies.of(partial));
    }

    @Test void TL02_invalidPoliciesRejected() {
        Duration d = Duration.ofSeconds(30), w = Duration.ofMinutes(5);
        assertThrows(IllegalArgumentException.class, () -> policy(0, 8192, 2048, d, 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, -1, 2048, d, 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 0, d, 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 2048, Duration.ZERO, 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 2048, d, 0, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 2048, d, 3, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 2048, d, 3, w, 0));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 2048, Duration.ofSeconds(31), 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 2048, d, 3, Duration.ofHours(24).plusSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24577, 8192, 2048, d, 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8193, 2048, d, 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 2049, d, 3, w, 1));
    }

    @Test void TL02_boundaryValuesAccepted() {
        assertDoesNotThrow(() -> policy(24576, 8192, 2048, Duration.ofSeconds(30), 3, Duration.ofHours(24), 1));
    }

    @Test void TL03_initialValuesForEveryTask() {
        for (Task task : Task.values()) {
            SocLlmTaskPolicy p = SocLlmTaskPolicies.defaults().forTask(task);
            assertEquals(24_576, p.maxInputBytes(), task.name());
            assertEquals(8_192, p.maxInstructionBytes(), task.name());
            assertEquals(2_048, p.maxOutputTokens(), task.name());
            assertEquals(Duration.ofSeconds(30), p.maxDeadline(), task.name());
            assertEquals(3, p.startsPerWindow(), task.name());
            assertEquals(Duration.ofMinutes(5), p.window(), task.name());
            assertEquals(1, p.maxInFlightPerActor(), task.name());
        }
    }
}
