package mp4.platform;

import java.util.UUID;

/**
 * Provider boundary for the Reporter report check. The draft is already validated and untrusted.
 * Returns the raw model text, which the caller validates. Gateway errors are thrown as
 * {@link mp4.maintainer.summary.SummaryContracts.SummaryFailure} with a safe code.
 */
public interface ReportClarifyProvider {
    String clarify(UUID actorId, String title, String description);

    /** Configured model identifier for sanitized telemetry, when available. */
    String modelName();
}
