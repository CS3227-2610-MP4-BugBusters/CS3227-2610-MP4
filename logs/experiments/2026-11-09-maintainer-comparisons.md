# Maintainer controlled comparisons

Compared two small validation tasks in isolated single-agent and implementation-plus-
review conditions. All four candidates passed the same frozen checks for their task;
review found no extra defects. The added review involved extra handoff time in this
sample, while earlier application reviews caught genuine concurrency/security issues.
Do not infer a general efficiency advantage from these small synthetic tasks.

| Condition/task | Tests | Coordinator wall seconds | Scope |
| --- | --- | --- | --- |
| single-title | 2/2 | 62.45 | validation.py only |
| multi-title | 2/2 | 140.13 | validation.py only |
| single-reason | 2/2 | 60.96 | validation.py only |
| multi-reason | 2/2 | 103.65 | validation.py only |

Froze protocol version 1 before dispatch (SHA-256
`d287a6734fe9df289589ca9f0634d31e74aca99c373da51008e8636dc3755e15`).
Used gpt-6-luna/high for all implementations and gpt-6-luna/low for independent
review, three-minute agent budgets, two repair limit and at most two concurrent
writers in different directories. No condition received another solution. Root
hash audits confirmed equivalent per-task starting files and unchanged tests/specs.
The exposed specialist tool-call counts were 3 (single title), 4 (multi title),
3 (single reason) and 5 (multi reason); coordinator calls are excluded. No post-
implementation failed check or reviewer-requested repair was reported. These are
coordinator-observed wall intervals including queue/scheduling/handoff overhead,
not CPU or pure model latency. Full traces/token/cost measurements were unavailable;
starting stubs were frozen, but each agent did not separately run pre-edit Red.

## Application result reuse

Ran the frozen controlled-provider comparison against the actual QueueSummaryService
using `reuse.gradle`: 10 identical authorized requests made 1 provider call with
reuse, versus 10 with explicit regeneration (9 calls avoided). Fresh input versions,
assignment, membership and model each caused a new call; revoked access caused no
additional call. Prompt-policy version changes were not experimentally exercised.

Measured local totals of 390603900 ns for reuse and 12691900 ns for regeneration.
The first condition includes JVM warmup; these values cannot establish a latency
saving. The provider returned deterministic empty findings, so this run proves call
accounting/invalidation only, not usefulness, factual grounding, live quotas or
provider prompt-cache hits. Tokens are unavailable for this controlled fixture.

Prepared two Maintainer examples and the 0-2 grounding/completeness/usefulness rubric
for the live context experiment. Model/quota settings remain unconfirmed; no live
trials or human scores are claimed. Keep the planned three runs per condition per
example unless the team explicitly revises the quota-constrained protocol.
