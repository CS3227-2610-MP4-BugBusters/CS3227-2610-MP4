# My Maintainer implementation plan

Status: I propose this SDD task baseline on 7 November 2026; I have not implemented or executed application tests.
Inputs: I use [product requirements](PRODUCT-SPEC.md), [technical design](TECHNICAL-SPEC.md), and [test matrix](TEST-MATRIX.md). I identify the reviewed file hashes or Git revision in each task handoff before implementation.

| Task | Dependencies | My work and completion evidence |
| --- | --- | --- |
| MNT-P0 | Product/technical draft | I resolve the shared contract decisions, establish synthetic fixtures and source boundaries, and trace Q1/T1/A1/R1/D1/S1/X1/S2 to tests. I record the real build/test command after inspecting the scaffold; I do not invent a successful command or assume a framework. |
| MNT-P1 | P0; current-user/report-query contracts | I test then implement MNT-READ-01 and the read-only React screens. I verify authenticated requests, filter semantics, safe DTOs, history, and empty/loading/failure states (MT-01/02/03/13). I use query fakes until the shared adapter exists. |
| MNT-P2 | P1; lifecycle/version/developer contracts | I test then implement MNT-TRIAGE-01 and MNT-ASG-01. I verify priority, assignment eligibility, expected versions, history, reassignment reasons and access loss with rollback and real PostgreSQL races (MT-04/05/06/07). |
| MNT-P3 | P1; proposal/invalidation/discussion contracts | I test then implement MNT-RES-01 and MNT-DISC-01. I verify return/close/reopen rules, stale proposals, invalidation versus closure races and discussion ownership (MT-08/09/10/11/12). I can work independently of the AI integration. |
| MNT-P4 | P1; gateway fake and queue-snapshot seam | I test then implement MNT-AI-01/MNT-AI-SEC-01: context selection, prompt, output validation, summary UI, stale snapshot rejection and guarded reuse (MT-14 through MT-23). I keep fake mode explicit and unavailable in production. |
| MNT-P5 | P2/P3/P4; real shared adapters and confirmed SoC access | I run real-auth/API/database integration, the cross-role flow, and bounded live SoC usefulness/security examples (MT-24/25). I record fixture/prompt/model versions, quota budget and actual results. I collect the frozen context/reuse experiments; I never treat fake output as real SoC evidence. |
| MNT-P6 | P5; independent verification and deployed candidate | I resolve independent findings, obtain teammate review, verify production behavior, and update role guide, acknowledgement and reflection contributions plus sanitized verified logs. I record exact revision, commands, exits and evidence, distinguishing any incomplete criteria (MT-26). |

## My task execution protocol

I use specification -> acceptance/security test design -> scoped implementation -> independent test/security review -> human/teammate acceptance. I update spec, tests and implementation together if reviewed behavior changes.

I coordinate a specification agent, implementation agent and separate verifier. Each brief names task ID, spec revision, acceptance IDs, allowed files/tools, dependencies, verification command, model/reasoning settings, time/token budget, concurrency and retry limit. I use one writer per file and separate worktrees for concurrent independent writers. I pass short handoffs with references instead of full histories. I retrieve additional code as needed.

I require the implementation agent to hand off candidate revision/diff, changed paths, checks/results, failed approaches, remaining dependencies and next action. I give the verifier read-only implementation scope; I require PASS/FAIL/PARTIAL per acceptance criterion and reproducible findings. I return failures to the implementation agent and reverify affected criteria, proposing no more than two repair rounds within the task budget. I resolve specification disagreements before resuming affected changes. I do not present an agent's assertion as test evidence or an agent review as teammate acceptance.

I record actual model settings and exposed usage, elapsed time, tool calls, handoffs, retries and findings. I stop at exhausted resource budgets and report the candidate state. I use deterministic provider fixtures for routine checks; I coordinate a small live-call budget against verified SoC limits. I do not guess provider quotas, cache hits or reasoning-token counts.

## My source boundary and integration conditions

I implement only Maintainer-owned frontend/backend/prompt/test modules and minimal agreed wiring under src/. I consume shared authentication, report/lifecycle, discussion and AI contracts; I do not create competing lifecycle implementations or write into another role's tables. I coordinate additive migrations and API changes with the relevant owner. I isolate synthetic fixtures and fakes from production adapters.

I treat authentication/provisioning and deployment as proposed separate shared assignments. I create their own task/spec scope only if those responsibilities are assigned. I do not infer them from a withdrawn workload allocation. I verify real session integration and PostgreSQL races before claiming the role is integrated, even if fakes made independent work possible.

I preserve the deployment constraints in release integration: I use team-managed hosting and separate development/production configuration, databases and credentials. I verify the chosen GitHub release/submission branch because the current remote uses main while the active project plan names master. I verify actual environment protections and deployed revision; I keep the GitHub Pages website a distinct team deliverable.

## My evaluation and finish conditions

I freeze comparable inputs, model/prompt settings, budgets and rubric before experiment runs. I contribute two legitimate Maintainer cases to context-selection evaluation. I propose a result-reuse comparison with identical authorized inputs and independently changed content, membership, permissions, assignment, prompt and model. I measure calls, latency, useful grounding and invalidation correctness. I contribute actual handoff/review evidence to coordination analysis; I claim a controlled comparison only when both conditions were run from isolated comparable baselines.

I record runnable tests under src/, shared agent/evaluation instructions under workflow/ where agreed, sanitized results under logs/, and role documentation/reflection contributions under docs/. I acknowledge permitted external ideas and MP3's document-organization reference; I import no previous-MP implementation. I keep execution results separate from the matrix's expected outcomes and report unavailable/inconclusive checks explicitly.

I finish only when the specification-linked checks pass on the release candidate, independent findings are resolved, real SoC usefulness has been assessed, the integrated browser flow is verified, and guides/evidence match the deployed behavior. I do not mark unexecuted tests, pending contract decisions or fake-backed integration as complete.
