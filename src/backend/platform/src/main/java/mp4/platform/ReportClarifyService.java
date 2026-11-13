package mp4.platform;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reporter report check (RC-03, RC-04, RC-05, RC-07, RC-08): validates the draft, calls the provider once
 * and returns only a validated result or a safe error. The caller supplies the trusted actor after its
 * session checks (RC-02). Replies and logs never contain draft text, quotes, questions or model output.
 */
final class ReportClarifyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportClarifyService.class);

    /** Status, API error code (null on success), Retry-After seconds for 429, and the JSON body. */
    record Reply(int status, String code, Integer retryAfterSeconds, String body) { }

    private final ReportClarifyProvider provider;

    ReportClarifyService(ReportClarifyProvider providerOrNull) {
        this.provider = providerOrNull;
    }

    Reply handle(UUID actorId, byte[] body) {
        long started = System.nanoTime();
        if (actorId == null) {
            recordTelemetry("unauthenticated", started, 0, 0);
            return error(401, "UNAUTHENTICATED", "Authentication required", null);
        }
        final ReportClarifyRules.Draft draft;
        try {
            draft = ReportClarifyRules.parseRequest(body);
        } catch (IllegalArgumentException invalid) {
            recordTelemetry("validation_failed", started, 0, 0);
            return error(400, "VALIDATION_FAILED", "The report check request is invalid", null);
        }
        if (provider == null) {
            recordTelemetry("unavailable", started, 0, 0);
            return unavailable();
        }

        final String raw;
        try {
            raw = provider.clarify(actorId, draft.title(), draft.description());
        } catch (SummaryFailure failure) {
            return mapFailure(failure, started);
        } catch (RuntimeException unexpected) {
            recordTelemetry("unavailable", started, 0, 0);
            return unavailable();
        }

        final ReportClarifyRules.Result result;
        try {
            result = ReportClarifyRules.validateOutput(raw, draft);
        } catch (IllegalArgumentException invalid) {
            recordTelemetry("invalid_output", started, 0, 0);
            return invalidOutput();
        }
        recordTelemetry("success", started, result.unclear().size(), result.missing().size());
        return new Reply(200, null, null, result.toJson());
    }

    private Reply mapFailure(SummaryFailure failure, long started) {
        return switch (failure.failureCode()) {
            case AI_BUSY -> {
                recordTelemetry("busy", started, 0, 0);
                yield error(409, "AI_BUSY", "A report check is already running", null);
            }
            case AI_RATE_LIMITED -> {
                recordTelemetry("rate_limited", started, 0, 0);
                yield error(429, "AI_RATE_LIMITED", "Report checks are rate limited", failure.retryAfterSeconds());
            }
            case AI_TIMEOUT -> {
                recordTelemetry("timeout", started, 0, 0);
                yield error(504, "AI_TIMEOUT", "The report check timed out", null);
            }
            case AI_INVALID_OUTPUT -> {
                recordTelemetry("invalid_output", started, 0, 0);
                yield invalidOutput();
            }
            case AI_UNAVAILABLE, UNAUTHENTICATED, FORBIDDEN, VALIDATION_FAILED, QUEUE_TOO_LARGE,
                    AI_STALE_SOURCE, NOT_IMPLEMENTED -> {
                recordTelemetry("unavailable", started, 0, 0);
                yield unavailable();
            }
        };
    }

    private static Reply unavailable() {
        return error(503, "AI_UNAVAILABLE", "Report checks are temporarily unavailable", null);
    }

    private static Reply invalidOutput() {
        return error(502, "AI_INVALID_OUTPUT", "The report check returned an invalid response", null);
    }

    /** Same shape as AuthHttp.error; messages are fixed text and never carry request or model data. */
    private static Reply error(int status, String code, String message, Integer retryAfterSeconds) {
        return new Reply(status, code, retryAfterSeconds, "{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"requestId\":\"" + UUID.randomUUID() + "\"}");
    }

    /** RC-08 / design decision 8: outcome, latency, token usage when present, item counts only. */
    private void recordTelemetry(String outcome, long startedAt, int unclearCount, int missingCount) {
        String model = "unavailable";
        try {
            if (provider != null) {
                String selectedModel = provider.modelName();
                if (selectedModel != null && selectedModel.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}")) {
                    model = selectedModel;
                }
            }
        } catch (RuntimeException ignored) {
            // Telemetry metadata must not change the result or expose exception details.
        }
        long durationMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        try {
            // The role gateway returns only the content, so provider token usage is not available here.
            LOGGER.info("event=reporter_report_check outcome={} model={} duration_ms={} input_tokens={} "
                    + "output_tokens={} unclear_count={} missing_count={}",
                    outcome, model, durationMillis, "unavailable", "unavailable", unclearCount, missingCount);
        } catch (RuntimeException ignored) {
            // Logging is best effort; the request keeps its application-level outcome.
        }
    }
}
