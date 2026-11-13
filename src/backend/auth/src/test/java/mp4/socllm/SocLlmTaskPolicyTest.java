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
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 2048, Duration.ofSeconds(61), 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 2048, d, 3, Duration.ofHours(24).plusSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> policy(61441, 8192, 2048, d, 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8193, 2048, d, 3, w, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(24576, 8192, 8193, d, 3, w, 1));
    }

    @Test void TL02_boundaryValuesAccepted() {
        assertDoesNotThrow(() -> policy(24576, 8192, 2048, Duration.ofSeconds(30), 3, Duration.ofHours(24), 1));
    }

    @Test void TL02_raisedCeilingsAreAcceptedAtTheirBoundary() {
        assertDoesNotThrow(() -> policy(61_440, 8_192, 8_192, Duration.ofSeconds(60), 3, Duration.ofMinutes(5), 1));
    }

    private static void assertPolicy(Task task, int in, int instr, int out, Duration deadline, int reservation) {
        SocLlmTaskPolicy p = SocLlmTaskPolicies.defaults().forTask(task);
        assertEquals(in, p.maxInputBytes(), task.name());
        assertEquals(instr, p.maxInstructionBytes(), task.name());
        assertEquals(out, p.maxOutputTokens(), task.name());
        assertEquals(deadline, p.maxDeadline(), task.name());
        assertEquals(3, p.startsPerWindow(), task.name());
        assertEquals(Duration.ofMinutes(5), p.window(), task.name());
        assertEquals(1, p.maxInFlightPerActor(), task.name());
        assertEquals(reservation, p.maxReservation(), task.name());
    }

    @Test void TL03_developerAndMaintainerKeepInitialValues() {
        for (Task task : new Task[] {Task.DEVELOPER_ASSIST, Task.MAINTAINER_QUEUE_SUMMARY}) {
            assertEquals(SocLlmTaskPolicy.initial(), SocLlmTaskPolicies.defaults().forTask(task), task.name());
            assertPolicy(task, 24_576, 8_192, 2_048, Duration.ofSeconds(30), 34_816);
        }
    }

    @Test void RC09_reporterAssistPolicyHasRaisedLimits() {
        assertPolicy(Task.REPORTER_ASSIST, 61_440, 8_192, 8_192, Duration.ofSeconds(60), 77_824);
    }
}
