package mp4.socllm;

import java.time.Duration;

/**
 * Server-owned limits for one SoCLaaS task. Callers and request data never choose a policy.
 * A policy can only tighten the transport ceilings, never raise them.
 */
public record SocLlmTaskPolicy(int maxInputBytes, int maxInstructionBytes, int maxOutputTokens,
        Duration maxDeadline, int startsPerWindow, Duration window, int maxInFlightPerActor) {
    static final int INPUT_BYTES_CEILING = 24_576;
    static final int INSTRUCTION_BYTES_CEILING = 8_192;
    static final int OUTPUT_TOKENS_CEILING = 2_048;
    static final Duration DEADLINE_CEILING = Duration.ofSeconds(30);
    /** Admission rows are retained for 24 hours, so a longer window could not be counted. */
    static final Duration WINDOW_CEILING = Duration.ofHours(24);

    public SocLlmTaskPolicy {
        if (maxInputBytes < 1 || maxInputBytes > INPUT_BYTES_CEILING
                || maxInstructionBytes < 1 || maxInstructionBytes > INSTRUCTION_BYTES_CEILING
                || maxOutputTokens < 1 || maxOutputTokens > OUTPUT_TOKENS_CEILING
                || !positiveAtMost(maxDeadline, DEADLINE_CEILING)
                || startsPerWindow < 1
                || !positiveAtMost(window, WINDOW_CEILING)
                || maxInFlightPerActor < 1) {
            throw new IllegalArgumentException("Invalid SoCLaaS task policy.");
        }
    }

    /** Initial limits that every task starts with. */
    static SocLlmTaskPolicy initial() {
        return new SocLlmTaskPolicy(24_576, 8_192, 2_048, Duration.ofSeconds(30), 3, Duration.ofMinutes(5), 1);
    }

    /** Largest token reservation a call under this policy can need. */
    int maxReservation() {
        return maxInstructionBytes + maxInputBytes + maxOutputTokens;
    }

    private static boolean positiveAtMost(Duration value, Duration ceiling) {
        return value != null && !value.isNegative() && !value.isZero() && value.compareTo(ceiling) <= 0;
    }
}
