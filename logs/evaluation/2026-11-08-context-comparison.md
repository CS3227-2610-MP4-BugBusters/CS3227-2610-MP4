# Context comparison: shared report migration review

## Human-readable result

Two independent agents reviewed the same local shared-report migration for production-integration readiness. Both said further integration work is needed. The broad-discovery agent explicitly identified missing account/proposal foreign keys; the targeted-start agent identified that existing Developer test fixtures do not yet consume the shared migration. Both found the missing migration framework and real authentication/routing integration.

This was an exploratory comparison of two prompts, not proof that either method is better or cheaper. Token counts, elapsed times and complete tool traces were unavailable. The agents saw an uncommitted worktree, not two immutable snapshots; a scoring rubric was not frozen before dispatch. Do not use this run to claim measured savings or a controlled benchmark.

## Setup and observed outputs

- Task: read-only assessment of whether the shared-report migration was ready for integration into a production app; identify concrete gaps with source paths. Neither agent was asked to change files or execute tests.
- Starting HEAD: `99f20e715d62f30b0402a909a529511bd71b8714`, plus the existing uncommitted shared-report migration, OpenSpec and logs. The two agents had access to the same shared workspace; we did not verify a complete file snapshot or lock it during the run.
- Run A: `context_broad`, GPT-6 Luna low, was asked to discover relevant project documents broadly. It reported reading repository instructions, shared-report OpenSpec and migration, Developer and Maintainer contracts and evidence. It flagged migration tracking/upgrades, absent identity and proposal foreign keys, service version checks, and real authentication/routing.
- Run B: `context_targeted`, GPT-6 Luna low, was given four starting paths: shared-report design, shared README and SQL, and checklist storage contract; it was allowed to retrieve more. It reported also reading Developer docs, stores, fixtures and shared migration tests. It flagged migration tracking/upgrades, fixture divergence and missing production authentication/routing and service-level validation.
- Both reported inspecting files and not running tests. The coordinator reviewed their written findings; no independent blind scoring or timing instrument was set up. No agent made repository edits.

## Interpretation and evidence limits

This illustrates two ways of supplying context: broad discovery and a small set of relevant entry points followed by retrieval. Each surfaced useful integration issues, but the file lists are agent-reported, not a measured account of every token. The prompts differed in starting paths, execution was concurrent, and no frozen rubric or independent timing/token telemetry was available. Agent output may have shared underlying knowledge and neither finding proves a production defect by itself. Evidence of the existing migration work is in `logs/shared/2026-11-08-shared-report-storage-review.md`.

A future controlled comparison should freeze task, starting snapshot, model, independent acceptance rubric and measured start/end before dispatch, use isolated copies, and capture actual input/output and cached/reasoning tokens where the tool exposes them. Treat unavailable values as unavailable. The single-agent comparison must not replace the repository's required integration workflow.
