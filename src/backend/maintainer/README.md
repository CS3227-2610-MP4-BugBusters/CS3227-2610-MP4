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

Connected `/api/maintainer/queue-summary` to real sessions and consistent queue
snapshots. Check current account/session access before delivery and cache reuse.
Read every member's version and assignment, but load source bodies only for the
20 context candidates; context remains capped at 24 KiB. Exclude private drafts,
private notes, identities and raw checklist bodies from provider input.

The shared AI owner can provide these Spring beans from `SummaryContracts`:

- `SummaryGateway`: SoC model identity and provider calls with separate trusted
  instructions and untrusted report JSON.
- `BudgetAndAdmission`: shared durable admission, quotas and a lease retained
  until the provider worker actually terminates.

Without both adapters, nonempty generation fails closed with `AI_UNAVAILABLE`.
An empty queue returns `providerMode: none` without a provider call. Runtime
deterministic providers remain disabled. Controlled test providers and the
explicit frontend development preview do not establish live SoC behavior.

## Checks

From the repository root, with PostgreSQL tools and a JDK configured:

```sh
python src/backend/auth/test/run_setup.py
python src/backend/auth/test/run_setup.py --tests mp4.auth.RunnerSetupTest --tests mp4.auth.MaintainerQueueSummaryIntegrationTest
```

The first command runs all backend checks. The second selects a bounded test
task using the same disposable database setup; CI retains the full check.
