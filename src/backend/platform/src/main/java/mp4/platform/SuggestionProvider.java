package mp4.platform;

import java.util.List;

/** Provider boundary for bounded, untrusted report context. No provider is configured yet. */
public interface SuggestionProvider {
    ProviderResponse suggest(String boundedTitle, String boundedDescription);

    /** Supplies the saved private checklist as bounded, untrusted context. */
    default ProviderResponse suggest(String boundedTitle, String boundedDescription, List<String> savedChecklist) {
        return suggest(boundedTitle, boundedDescription);
    }

    /** Configured model identifier for sanitized telemetry, when available. */
    default String modelName() { return null; }

    enum FailureKind { TIMEOUT, INVALID, UNAVAILABLE, BUSY }

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
