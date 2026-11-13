package mp4.platform;

import java.time.Duration;
import java.util.UUID;
import mp4.maintainer.summary.SummaryContracts.FailureCode;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;
import mp4.socllm.SocLlmContracts;
import mp4.socllm.SocLlmContracts.Role;
import mp4.socllm.SocLlmContracts.Task;

/** Reporter report-check adapter over the shared, role-bound SoC gateway (REPORTER_ASSIST). */
public final class SocLlmReportClarifyProvider implements ReportClarifyProvider {
    static final int MAX_OUTPUT_TOKENS = 8_192;
    static final Duration DEADLINE = Duration.ofSeconds(60);

    private final SocLlmContracts.RoleGateway gateway;
    private final String model;

    public SocLlmReportClarifyProvider(SocLlmContracts.RoleGateway gateway, String model) {
        if (gateway == null || model == null || model.isBlank()) {
            throw new IllegalArgumentException("Invalid shared SoC report check configuration");
        }
        this.gateway = gateway;
        this.model = model;
    }

    @Override public String modelName() { return model; }

    @Override
    public String clarify(UUID actorId, String title, String description) {
        if (actorId == null) throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        ReportClarifyRules.Draft draft = new ReportClarifyRules.Draft(title, description);
        try {
            return gateway.generate(new SocLlmContracts.Call(Role.REPORTER, actorId, Task.REPORTER_ASSIST,
                    ReportClarifyRules.instructions(), ReportClarifyRules.modelInput(draft),
                    MAX_OUTPUT_TOKENS, DEADLINE));
        } catch (SummaryFailure gatewayFailure) {
            throw gatewayFailure;
        } catch (RuntimeException unavailable) {
            // Drop the cause: its message may carry provider or draft data.
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
    }
}
