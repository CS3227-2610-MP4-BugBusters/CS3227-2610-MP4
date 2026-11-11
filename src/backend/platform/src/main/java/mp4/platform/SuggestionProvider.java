package mp4.platform;

import java.util.List;
import java.util.UUID;

/** Provider boundary for bounded, untrusted report context. */
public interface SuggestionProvider {
    ProviderResponse suggest(String boundedTitle, String boundedDescription);

    /** Supplies the saved private checklist as bounded, untrusted context. */
    default ProviderResponse suggest(String boundedTitle, String boundedDescription, List<String> savedChecklist) {
        return suggest(boundedTitle, boundedDescription);
    }

    /** Trusted server-side actor identity for shared role-gateway calls. */
    default ProviderResponse suggest(UUID actorId, String boundedTitle, String boundedDescription,
            List<String> savedChecklist) {
        return suggest(boundedTitle, boundedDescription, savedChecklist);
    }

    /** Configured model identifier for sanitized telemetry, when available. */
    default String modelName() { return null; }

    enum FailureKind { TIMEOUT, INVALID, UNAVAILABLE, BUSY, RATE_LIMITED, INPUT_TOO_LARGE }

    /** Safe provider failure category for API mapping; messages must not contain provider data. */
    final class Failure extends IllegalStateException {
        private final FailureKind kind;

        public Failure(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public FailureKind kind() { return kind; }
    }

    /** Raw provider output and reported usage remain untrusted until validated. */
    record ProviderResponse(String rawOutput, Usage usage) { }

    /** Token counts may be absent when the provider does not report them. */
    record Usage(Long inputTokens, Long outputTokens) { }
}
