# Maintainer implementation plan

Status: task baseline proposed on 7 November 2026. Completed the isolated MNT-P1 frontend increment locally; shared-service integration remains pending. Results are tracked separately below.
Inputs: [product requirements](PRODUCT-SPEC.md), [technical design](TECHNICAL-SPEC.md), and [test matrix](TEST-MATRIX.md). Identify the reviewed file hashes or Git revision in each task handoff before implementation.

| Task | Dependencies | Work and completion evidence |
| --- | --- | --- |
| MNT-P0 | Product/technical draft | Resolve the shared contract decisions, establish synthetic fixtures and source boundaries, and trace Q1/T1/A1/R1/D1/S1/X1/S2 to tests. Record the real build/test command after inspecting the scaffold; do not invent a successful command or assume a framework. |
| MNT-P1 | P0; current-user/report-query contracts | Test then implement MNT-READ-01 and the read-only React screens. Verify authenticated requests, filter semantics, safe DTOs, history, and empty/loading/failure states (MT-01/02/03/13). Use query fakes until the shared adapter exists. |
| MNT-P2 | P1; lifecycle/version/developer contracts | Test then implement MNT-TRIAGE-01 and MNT-ASG-01. Verify priority, assignment eligibility, expected versions, history, reassignment reasons and access loss with rollback and real PostgreSQL races (MT-04/05/06/07). |
| MNT-P3 | P1; proposal/invalidation/discussion contracts | Test then implement MNT-RES-01 and MNT-DISC-01. Verify return/close/reopen rules, stale proposals, invalidation versus closure races and discussion ownership (MT-08/09/10/11/12). Can work independently of the AI integration. |
| MNT-P4 | P1; gateway fake and queue-snapshot seam | Test then implement MNT-AI-01/MNT-AI-SEC-01: context selection, prompt, output validation, summary UI, stale snapshot rejection and guarded reuse (MT-14 through MT-23). Keep fake mode explicit and unavailable in production. |
| MNT-P5 | P2/P3/P4; real shared adapters and confirmed SoC access | Run real-auth/API/database integration, the cross-role flow, and bounded live SoC usefulness/security examples (MT-24/25). Record fixture/prompt/model versions, quota budget and actual results. Collect the frozen context/reuse experiments; never treat fake output as real SoC evidence. |
| MNT-P6 | P5; independent verification and deployed candidate | Resolve independent findings, obtain teammate review, verify production behavior, and update role guide, acknowledgement and reflection contributions plus sanitized verified logs. Record exact revision, commands, exits and evidence, distinguishing any incomplete criteria (MT-26). |

## Task execution protocol

Use specification -> acceptance/security test design -> scoped implementation -> independent test/security review -> human/teammate acceptance. Update spec, tests and implementation together if reviewed behavior changes.

I'll coordinate a specification agent, implementation agent and separate verifier. Each brief names task ID, spec revision, acceptance IDs, allowed files/tools, dependencies, verification command, model/reasoning settings, time/token budget, concurrency and retry limit. Use one writer per file and separate worktrees for concurrent independent writers. Pass short handoffs with references instead of full histories. Retrieve additional code as needed.

Require the implementation agent to hand off candidate revision/diff, changed paths, checks/results, failed approaches, remaining dependencies and next action. Give the verifier read-only implementation scope; require PASS/FAIL/PARTIAL per acceptance criterion and reproducible findings. Return failures to the implementation agent and reverify affected criteria, proposing no more than two repair rounds within the task budget. Resolve specification disagreements before resuming affected changes. Do not present an agent's assertion as test evidence or an agent review as teammate acceptance.

Record actual model settings and exposed usage, elapsed time, tool calls, handoffs, retries and findings. Stop at exhausted resource budgets and report the candidate state. Use deterministic provider fixtures for routine checks; coordinate a small live-call budget against verified SoC limits. Do not guess provider quotas, cache hits or reasoning-token counts.

## Source boundary and integration conditions

Implement only Maintainer-owned frontend/backend/prompt/test modules and minimal agreed wiring under src/. Consume shared authentication, report/lifecycle, discussion and AI contracts; do not create competing lifecycle implementations or write into another role's tables. Coordinate additive migrations and API changes with the relevant owner. Isolate synthetic fixtures and fakes from production adapters.

Treat authentication/provisioning and deployment as proposed separate shared assignments. Create their own task/spec scope only if those responsibilities are assigned. Do not infer them from a withdrawn workload allocation. Verify real session integration and PostgreSQL races before claiming the role is integrated, even if fakes made independent work possible.

Preserve the deployment constraints in release integration: use team-managed hosting and separate development/production configuration, databases and credentials. Verify the chosen GitHub release/submission branch because the current remote uses main while the active project plan names master. Verify actual environment protections and deployed revision; keep the GitHub Pages website a distinct team deliverable.

## Evaluation and finish conditions

Freeze comparable inputs, model/prompt settings, budgets and rubric before experiment runs. Contribute two legitimate Maintainer cases to context-selection evaluation. Propose a result-reuse comparison with identical authorized inputs and independently changed content, membership, permissions, assignment, prompt and model. Measure calls, latency, useful grounding and invalidation correctness. Contribute actual handoff/review evidence to coordination analysis; claim a controlled comparison only when both conditions were run from isolated comparable baselines.

Record runnable tests under src/, shared agent/evaluation instructions under workflow/ where agreed, sanitized results under logs/, and role documentation/reflection contributions under docs/. Acknowledge permitted external ideas and MP3's document-organization reference; import no previous-MP implementation. Keep execution results separate from the matrix's expected outcomes and report unavailable/inconclusive checks explicitly.

Finish only when the specification-linked checks pass on the release candidate, independent findings are resolved, real SoC usefulness has been assessed, the integrated browser flow is verified, and guides/evidence match the deployed behavior. Do not mark unexecuted tests, pending contract decisions or fake-backed integration as complete.

## Read-only increment

Started MNT-P1 against [READ-ONLY-INCREMENT-SPEC.md](READ-ONLY-INCREMENT-SPEC.md), revision MNT-READ-SPEC-1. Limited implementation to `src/frontend/maintainer/`: an injectable React feature, proposed HTTP adapter and development-only synthetic adapter. This does not implement shared authentication, Java endpoints, persistence, mutations or AI.

Use `npm ci` then `npm run verify` from that package once its tooling exists. Require typecheck, deterministic contract/component tests and production build, followed by independent QR-01 through QR-08 review and browser smoke evidence. Record actual results after execution; backend portions of Q1/MT-01/02/03 remain pending real services.

Completed the isolated frontend implementation and independent QR-01 through QR-08 verification. `npm run verify` passed typecheck, 30 tests and production build after one sorting repair. Chrome demo and production-entry smoke checks also passed. See [execution evidence](../../logs/maintainer/2026-11-07-read-only-increment.md). This completes the fake-backed frontend portion only; shared Java endpoints, trusted sessions and database integration are still separate work.
