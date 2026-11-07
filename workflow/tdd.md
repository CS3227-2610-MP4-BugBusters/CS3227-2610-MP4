# Test-driven development with subagents

Use the approved OpenSpec change and relevant interface contracts as the source of expected behavior. The main agent coordinates the sequence. Each specialist receives the task ID, required paths, starting revision, and allowed edits.

Dispatch the named project roles below using their defaults in `.codex/agents/`. Use a fresh context for independent reviews. If a configured model or effort is unavailable, report it before substituting a setting.

## 1. Red

Spawn a fresh `test_designer` before application code changes. It writes a focused test from the requirements and runs it against the baseline. Record the exact command, baseline revision, changed test paths, and observed failure.

The main agent checks that the test fails for the behavior being added. A missing fixture, unresolved API contract, unavailable database, or unrelated error blocks this gate. The designer records the exact missing input and the intended case when it cannot run the test.

## 2. Independently verify the Red baseline

Spawn a fresh `baseline_verifier` with the approved spec and contract, test and runner paths, fixtures, starting revision, and Red evidence log. The verifier reviews the assertions and the evidence and reruns Red against the same baseline. It checks that the required setup controls pass, that the failure comes from the behavior being added, and that the log describes the actual run and its limits. It compares the recorded checksums or commit with the files under review.

The baseline verifier reports a verdict and evidence. It does not edit tests, logs, or application code. Send findings to the test designer for correction and repeat verification before requesting approval. An unreproduced or setup-blocked Red cannot pass this gate.

## 3. Approve the test baseline

Stop for the feature owner's approval of the verified assertions and expected results. Record the approved test commit or checksum, including any fixtures and runner settings that affect the verdict. Changes to the approved tests require a new baseline review and owner approval.

## 4. Green and refactor

Spawn `implementer` with the approved spec, test baseline, and Red result. It implements the behavior and reruns the approved test. It may add coverage. It keeps the approved assertions, fixtures, and runner settings intact. After any cleanup, rerun the approved test and relevant regressions. Record the candidate revision and results.

## 5. Independently verify the candidate

Spawn a fresh `candidate_verifier` with the approved spec, test baseline, candidate revision, and verification command. The verifier runs the tests and checks that the baseline assertions ran as approved. It reports a verdict with evidence for each criterion. It does not edit application code, approved tests, or runner settings.

For authorization or AI security changes, spawn `security_reviewer` on the same candidate. Give it the applicable security criteria and ask it to look for bypasses beyond the planned tests. Route failures to the implementer and recheck the updated candidate.

## Evidence

Keep a sanitized record in `logs/` with the run ID, agent role, actual model and reasoning effort, task and revision, context supplied and retrieved, exact checks and outcomes, and any fix and recheck. Record token usage when the trace exposes it. Record missing measurements as unavailable.
