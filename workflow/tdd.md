# Test-driven development and review

First select the lightweight or full path in [workflow selection](workflow-selection.md).
For the lightweight path, one agent may write the focused test, verify the
expected failure where applicable, implement and run relevant checks. Review
its own diff and record the evidence; do not claim independent verification.
For documentation or presentation-only work, use proportionate inspection
instead of creating a Red test. The remaining numbered gates describe the full
path; they are not mandatory for the lightweight path.

Use the approved OpenSpec change and relevant interface contracts as the source of expected behavior. The main agent coordinates the sequence. Each specialist receives the task ID, required paths, starting revision, and allowed edits.

Dispatch the named project roles below using their defaults in `.codex/agents/`. Use a fresh context for independent reviews. If a configured model or effort is unavailable, report it before substituting a setting.

## 1. Red

Spawn a fresh `test_designer` before application code changes. It writes a focused test from the requirements and runs it against the baseline. Record the exact command, baseline revision, changed test paths, and observed failure.

The main agent checks that the test fails for the behavior being added. A missing fixture, unresolved API contract, unavailable database, or unrelated error blocks this gate. The designer records the exact missing input and the intended case when it cannot run the test.

## 2. Independently verify the Red baseline

Spawn a fresh `baseline_verifier` with the approved spec and contract, test and runner paths, fixtures, starting revision, and Red evidence log. The verifier reviews the assertions and the evidence and reruns Red against the same baseline. It checks that the required setup controls pass, that the failure comes from the behavior being added, and that the log describes the actual run and its limits. It compares the recorded checksums or commit with the files under review.

The baseline verifier reports a verdict and evidence. It does not edit tests, logs, or application code. Send findings to the test designer for correction and repeat verification before requesting approval. An unreproduced or setup-blocked Red cannot pass this gate.

## 3. Confirm authorization to proceed

Summarize the agreed behavior, verifier's verdict and any remaining decisions in plain language. The independent verifier reviews the tests, fixtures and hashes. The feature owner resolves open product decisions. If the owner has already authorized implementation and the verified baseline matches the agreed behavior, proceed; otherwise ask whether to proceed.

Treat the feature owner's clear go-ahead as approval to implement against that verified baseline. Record the approved test commit or checksum, including any fixtures and runner settings that affect the verdict. Changes to the approved tests require a new baseline review and owner approval.

## 4. Green and refactor

Spawn `implementer` with the approved spec, test baseline, and Red result. It implements the behavior and reruns the approved test. It may add coverage. It keeps the approved assertions, fixtures, and runner settings intact. After any cleanup, rerun the approved test and relevant regressions. Record the candidate revision and results.

## 5. Independently verify the candidate

Spawn a fresh `candidate_verifier` with the approved spec, test baseline, candidate revision, and verification command. The verifier runs the tests and checks that the baseline assertions ran as approved. It reports a verdict with evidence for each criterion. It does not edit application code, approved tests, or runner settings.

For authorization or AI security changes, spawn `security_reviewer` on the same candidate. Give it the applicable security criteria and ask it to look for bypasses beyond the planned tests. Route failures to the implementer and recheck the updated candidate.

## Evidence

Start each evidence record with a short, plain-language summary that a teammate can understand without reading hashes: the goal, what changed, checks and outcomes, review findings and fixes, and what remains open. Below it, retain a technical evidence section with the sanitized run ID, agent role, actual model and reasoning effort, task and revision, context supplied and retrieved, exact checks and outcomes, file hashes where needed, and any fix and recheck. Record token usage when the trace exposes it. Record missing measurements as unavailable.

For subsequent logs, use the detailed structure demonstrated by
`logs/developer/2026-11-09-resolution-submission.md`, scaled to the work:

1. **Summary and scope:** Explain the goal, resulting behavior, outcome and
   remaining limitations in plain language.
2. **Implementation and coordination:** Record the starting revision and
   uncommitted state, the relevant requirements and paths, who did each task,
   what context was supplied or retrieved, and any handoffs or interruptions.
3. **Findings and fixes:** Describe each material test or review finding, what
   changed in response, and who or what rechecked it. Distinguish test or setup
   errors from application failures and identify changed test baselines.
4. **Verification:** Record commands, evidence locations and outcomes, and
   distinguish mocked UI, synthetic sessions, real authenticated API,
   database, browser, hosted and deployment checks. Include blocked checks and
   the actual revision or worktree state tested.
5. **Reflection and resources:** Record actual model/effort and token, cache,
   reasoning, time or cost measurements only when available. Explain observed
   coordination and context choices, their benefits and overhead, and the limits
   of any comparison. Mark missing measurements unavailable; never infer savings.

Keep a short historical checkpoint short when no further evidence was captured;
link related follow-up evidence and explicitly identify missing details rather
than inventing agent actions, results or measurements.

For future runs, record a concise timeline of dispatches, handoffs, commands,
findings, fixes and rechecks, with agent roles, actual models and timestamps when
available. Record context supplied and relevant files retrieved, and link to
sanitized trace evidence where available. For each identifiable agent and the
coordinator, record reported input, cached input, output, reasoning output and
total tokens, plus elapsed time when available. Identify the measurement source
and scope, and avoid double-counting cumulative updates or overlapping totals.
Mark anything unavailable or unattributable; do not infer cost, quota usage or
savings from token counts. Avoid copying full transcripts or sensitive data into
the log or subsequent handoffs. Prefer a short summary and evidence references.
