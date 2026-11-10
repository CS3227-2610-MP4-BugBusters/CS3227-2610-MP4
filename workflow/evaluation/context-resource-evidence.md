# Context engineering and resource-aware use: MP4 evidence

Purpose: collect evidence for both the application and development workflow. The project requirement asks for evidence in both. Lecture 9 emphasizes clear minimal instructions, task-specific tools, targeted retrieval, structured notes for long tasks, model and effort choices, and evaluating outcomes rather than tokens alone. Follow the actual AI provider and project requirements; do not assume caching, token counts, or cost features are available.

## App: context and resource controls

Complete one record per AI capability and evaluation run. For Developer assistance, distinguish checklist drafting from response assessment; assessment remains subject to the agreed release scope.

| Item | Record actual information |
| --- | --- |
| Capability, run ID, revision and environment | |
| Authorized actor and source | Role and synthetic fixture IDs; avoid real personal data |
| Task and intended outcome | |
| Context selected | Fields and versions included; why each is needed |
| Context excluded | Sensitive fields or unrelated reports omitted; authorization check |
| Structure and instructions | Stable instruction version, separation of report content from instructions, expected output schema |
| Freshness | Source versions, post-generation access check and stale-result handling |
| Resource controls | Input/output budget, timeout, concurrency/rate limits, reuse or cache policy if actually supported |
| Usage | Provider-reported input, output, cached/reasoning tokens and request count where exposed; otherwise unavailable |
| Outcome | Result quality, latency, failure/timeout, correctness and grounding against fixture |
| Evidence | Sanitized test output, log location and revision; do not record secrets or raw private content |

App acceptance evidence should include an ordinary valid case, unauthorized/unrelated context excluded, source change or reassignment during a request, timeout or provider failure, and output checked against supplied evidence. Evaluate usefulness as well as security: rejecting every request is not a success. Validate these with deterministic provider fixtures before any bounded live calls. The gateway owner determines actual instrumentation and limits.

## Development: per-task record

| Item | Record actual information |
| --- | --- |
| Task, run ID, starting revision and worktree state | |
| Intended outcome and acceptance tests | |
| Agent role, model and reasoning effort | Include coordinator and all specialists |
| Context supplied | Exact spec, contract, code, tests and task instructions supplied to each role |
| Context retrieved | Relevant files or queries actually used, if exposed; do not invent a complete trace |
| Resources | Elapsed time, requests/tool calls, retries, input/output/cached/reasoning tokens where actually exposed; mark missing data unavailable |
| Result and quality | Tests, independent findings, defect fixes and verification; note blocked checks |
| Reflection | What was useful or excessive, tradeoffs, and one evidence-supported change for a future run |
| Evidence location | Sanitized logs, tests and commit or file hashes |

Use clear task boundaries and link the relevant files; use subagents when independent review or parallel work adds value, not automatically for every task. Keep concise notes: goal, current state, decisions, findings, open questions, next action and change log. Apply the existing TDD/security gates and avoid duplicate runs without a reason.

## Comparable experiment (team-plan commitment)

Follow team-plan section 12 for the controlled comparison. The assignment requires
concrete experiments and result analysis; the team plan selects this protocol.
Freeze the fixtures and budget before running, and record any agreed revision.

1. Choose the same well-scoped task, starting revision, acceptance criteria and evaluation tests for each workflow. Use isolated copies and ensure neither run sees the other's solution.
2. Define the workflow variants and model/effort settings in advance. Include the coordinator and specialists in the multi-agent total. Keep the required project workflow for the version integrated into the feature branch.
3. Define the rubric before running: e.g. pass/fail for each acceptance test, authorization/security findings, unresolved defects and human corrections. Record durations and available token usage per run.
4. Compare quality, time and actual resource usage. Report missing telemetry, failures and retries. A single task is a limited observation; do not infer a token or cost saving without measured comparable totals.

## Context and usage caveats

Lecture 9 discusses prompt caching, reasoning management and compaction as possible cost-management techniques. Their availability and metrics vary by provider and tool. Stable instructions placed before changing task context can improve prefix reuse where caching is supported, but do not claim a cache hit or savings without provider evidence. Compaction can lose information, so record key decisions and current state explicitly. Output-token caps are workload-dependent: set and test appropriate limits, and record truncation rather than copying a lecture example blindly.
