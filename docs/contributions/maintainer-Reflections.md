# Maintainer contribution to Reflections

Built authentication/provisioning and the Maintainer workflow with OpenSpec planning,
executable failing tests, implementation and specialist verification where available.
Used MP2/MP3 documentation as framework/deployment references and developed new
application code for MP4. Used Codex and its specialist agents for implementation,
test design and review; the commit history and bounded evidence logs record those
interactions. The exact agent roles came from the repository workflow rather than
a requirement for five agents in the assignment.

Found meaningful defects through integration: stale versions, proposal invalidation,
checklist Share versus closure, account-disable races and delayed AI responses.
Kept these checks focused on authorization and atomic data changes. Reduced repeated
full-suite runs during development and reserved them for integration checkpoints.

Kept shared checklist interfaces compatible and added auth checks at the application
boundary. Connected real database snapshots to the shared SoC provider/admission
adapters; generation fails closed when they are not configured. Controlled providers verify context
and revocation behavior; they do not prove live model grounding, cost or latency.

Independent review was temporarily unavailable because agent capacity/usage was
exhausted. Reported that limitation and retained the draft checkpoint. Once agents
became available, fresh review passed the release tooling and caught a dropped
wrapper-checksum gate during development; restored it before delivery.

Ran the frozen single-agent versus implementation-plus-review comparison on two
isolated synthetic validation tasks. All four candidates passed; reviewers found no
extra defects in that sample, while review/handoff increased observed wall time.
The tasks were tiny and timings include coordinator scheduling, so do not generalize
these results to complex application work. See the [comparison results](../../logs/experiments/2026-11-09-maintainer-comparisons.md).

Measured 1 provider call for 10 unchanged requests with reuse versus 10 calls with
regeneration using the actual summary service and controlled ports. Source,
assignment, membership and model changes invalidated reuse; revoked access made no
extra call. This demonstrates application-level call savings, not live provider
caching, grounding or latency improvement. Tokens and cost were unavailable.

Ran synthetic development-agent security and workflow cases with frozen tests,
scope audits, explicit failure reporting and a repair/recheck. Observed voluntary
refusal of malicious instructions; did not establish OS-level denial or complete
access telemetry. See [security results](../../logs/security/2026-11-09-development-agent-cases.md)
and [workflow results](../../logs/agent-runs/2026-11-09-workflow-cases.md).

Ran the two prepared Maintainer examples and a combined malicious-input case through
the real local application on 12 November. With `qwen3.6:35b`, one legitimate request
timed out, another returned a summary and reused it successfully, and the malicious
case was rate-limited. With `qwen3.5:9b`, all three timed out. A returned answer
incorrectly treated information present in a description as missing; the separate
structured fields were empty. This is evidence that valid JSON and passing
safety tests do not establish useful, grounded answers. These were sequential
diagnostic trials, not a controlled model comparison; the first successful trial
also included an additional browser-test report in its matching queue.

Preserved domain state on each observed failure. Cannot claim prompt-injection
resistance from a rate-limited or timed-out call, and have not completed the planned
repeated context comparison or human scoring. Provider cost, actual quotas, deployed
connectivity and production rollback remain unverified. Recorded the detailed scope
in [release checks](../../logs/maintainer/2026-11-12-release-checks.md).
These contributions still need coordinated team review and final guide/site assembly.

Followed the failed live trials with a focused repair cycle: independently verified
the failing context/configuration tests, omitted null context placeholders, clarified
cross-field evidence and concise overviews, and selected a separately configured
Maintainer model using a teammate's measured example as a starting point. This
demonstrates why live evaluation matters after deterministic safety checks. Recorded
the actual follow-up and its limits in [repair results](../../logs/maintainer/2026-11-12-summary-live-repair.md);
did not treat a different model as automatically correct or alter quotas to force a pass.
The first regression fixture assumed nulls, while the real schema used empty
strings. Added a storage-shaped failing test and a populated-field control, then
independently verified the small omission fix. The final Qwen trials all returned
valid summaries within 4.7–13.9 seconds; the description-only facts were recognized
and the malicious case was treated as an attempt rather than executed instructions.
Kept the output validator strict and rejected Llama as a release choice after its
unsafe/malformed trial outputs; Gemma's service was unavailable. These are diagnostic examples, not a controlled
model comparison or completed human scoring.
