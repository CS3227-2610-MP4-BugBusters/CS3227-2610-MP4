# Maintainer backend

I use the shared report aggregate for reads, priority, assignment, resolution
review and discussion. Account eligibility, report versions, proposal changes
and history commit in one transaction. Kept the Developer checklist constructor
compatible; the application supplies an observer that records changed Share in
the same transaction.

Registered canonical report/checklist SQL and the additive Maintainer migration
under `mp4_shared_schema_history`, separately from authentication migrations.
Verified fresh startup, compatible manual schemas and restart preservation.
Reject missing or incompatible canonical defaults before registering a manual
schema. Reconcile those defaults with the shared migration owner before restarting;
startup does not silently repair them or rewrite existing rows.

## Summary integration

Added optional `SOC_LLM_MODEL_MAINTAINER` for this role's model. A blank or absent
override retains the required shared `SOC_LLM_MODEL`; Reporter and Developer
settings are unaffected. Evaluated `llama3.1:8b` for speed but found format and
grounding failures; `gemma4:26b` was unavailable. The example selects `qwen3.6:35b`
with the repaired context/prompt. Recorded results separately from configuration.
Keep the shared model,
keys, budgets and enable flag explicit; this override does not enable AI by itself.

Prepared the summary context without null or blank optional-field placeholders. Preserved
description facts and instructed the model to check all supplied text before
claiming information is missing. Advanced the internal context/cache policy while
retaining the existing `MNT-SUMMARY-1` response schema. These safeguards do not
guarantee factual accuracy; summaries still require review.

Connected `/api/maintainer/queue-summary` to real sessions and consistent queue
snapshots. Check current account/session access before delivery and cache reuse.
Read every member's version and assignment, but load source bodies only for the
20 context candidates; context remains capped at 24 KiB. Exclude private drafts,
private notes, identities and raw checklist bodies from provider input.

The [MP4 SoCLLM module](../socllm/README.md) supplies these Spring beans from
`SummaryContracts` when explicitly enabled with complete Maintainer settings:

- `SummaryGateway`: SoC model identity and provider calls with separate trusted
  instructions and untrusted report JSON.
- `BudgetAndAdmission`: shared durable admission, quotas and a lease retained
  until the provider worker actually terminates.

Without both adapters, nonempty generation fails closed with `AI_UNAVAILABLE`.
An empty queue returns `providerMode: none` without a provider call. Runtime
deterministic providers remain disabled. Controlled test providers and the
explicit frontend development preview do not establish live SoC behavior.

Keep `SOC_LLM_ENABLED` disabled until the deployment's model, keys, budgets and
connectivity are confirmed. The shared module routes Reporter, Developer and
Maintainer keys separately; only the Maintainer queue-summary consumer is currently
connected to an application endpoint. Role services retain authorization, context
selection and output validation.

## Checks

From the repository root, with PostgreSQL tools and a JDK configured:

```sh
python src/backend/auth/test/run_setup.py
python src/backend/auth/test/run_setup.py --tests mp4.auth.RunnerSetupTest --tests mp4.auth.MaintainerQueueSummaryIntegrationTest
```

The first command runs all backend checks. The second selects a bounded test
task using the same disposable database setup; CI retains the full check.
