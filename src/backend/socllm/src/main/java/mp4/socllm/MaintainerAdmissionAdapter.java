package mp4.socllm;

import java.time.Duration;
import java.util.Objects;
import mp4.maintainer.summary.SummaryContracts.Actor;
import mp4.maintainer.summary.SummaryContracts.AdmissionLease;
import mp4.maintainer.summary.SummaryContracts.BudgetAndAdmission;
import mp4.maintainer.summary.SummaryContracts.FailureCode;
import mp4.maintainer.summary.SummaryContracts.ModelIdentity;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;
import mp4.maintainer.workflow.MaintainerContracts.Role;

/** Adapts the existing authenticated Maintainer summary port to the shared admission store. */
public final class MaintainerAdmissionAdapter implements BudgetAndAdmission {
    private static final int MAX_INSTRUCTION_BYTES = 8_192;
    private static final int MAX_REPORT_BYTES = 24_576;
    private static final int MAX_OUTPUT_TOKENS = 2_048;

    private final SocLlmContracts.RoleAdmission admissions;

    public MaintainerAdmissionAdapter(SocLlmContracts.RoleAdmission admissions) {
        this.admissions = Objects.requireNonNull(admissions);
    }

    @Override
    public AdmissionLease acquire(Actor actor, ModelIdentity model, Duration lease) {
        SocLlmTransactionBoundary.requireNoCallerTransaction();
        if (actor == null || !actor.active() || actor.role() != Role.MAINTAINER) {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
        // The summary port does not expose serialized input at admission time. Reserve its maximum
        // instruction, report-data and output bounds so the configured TPM budget fails closed.
        int reservation = MAX_INSTRUCTION_BYTES + MAX_REPORT_BYTES + MAX_OUTPUT_TOKENS;
        return admissions.acquire(SocLlmContracts.Role.MAINTAINER, actor.accountId(),
                SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY, reservation, lease);
    }
}
