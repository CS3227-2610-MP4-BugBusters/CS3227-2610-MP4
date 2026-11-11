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
    private final SocLlmContracts.RoleAdmission admissions;
    private final SocLlmTaskPolicies policies;

    public MaintainerAdmissionAdapter(SocLlmContracts.RoleAdmission admissions) {
        this(admissions, SocLlmTaskPolicies.defaults());
    }

    public MaintainerAdmissionAdapter(SocLlmContracts.RoleAdmission admissions, SocLlmTaskPolicies policies) {
        this.admissions = Objects.requireNonNull(admissions);
        this.policies = Objects.requireNonNull(policies);
    }

    @Override
    public AdmissionLease acquire(Actor actor, ModelIdentity model, Duration lease) {
        SocLlmTransactionBoundary.requireNoCallerTransaction();
        if (actor == null || !actor.active() || actor.role() != Role.MAINTAINER) {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
        // The summary port does not expose serialized input at admission time. Reserve its maximum
        // instruction, report-data and output bounds from its task policy so the TPM budget fails closed.
        int reservation = policies.forTask(SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY).maxReservation();
        return admissions.acquire(SocLlmContracts.Role.MAINTAINER, actor.accountId(),
                SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY, reservation, lease);
    }
}
