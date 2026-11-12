# Tasks

Status: reconciled merged implementation and local release evidence on 12 November
2026 at source 0b35be5. Checked completed implementation tasks against the existing
independent evidence and the new real-session/API/browser checks. Kept owner-review,
live AI acceptance, repeated context evaluation, deployment and final deliverables
open where their criteria are not met. See logs/maintainer/2026-11-12-release-checks.md;
historical logs retain their original results.

For each behavior task, follow `workflow/tdd.md`: test_designer, fresh
baseline_verifier, feature-owner approval of the verified Red, implementer,
fresh candidate_verifier and separate security_reviewer for authorization/AI.
Record actual fixture/runner/spec and candidate references, commands and outcomes.
An unavailable contract, fixture or runner is BLOCKED, not a passing Red.

## 0. Shared foundation first

- [ ] 0.1 Deliver the `add-shared-authentication` change as Maintainer owner, with Reporter-owner review. Publish the fixed-role provisioning, login/logout, CSRF and trusted-current-user contracts; verify real sessions and denial controls before claiming identity integration. Follow its bounded TDD tasks; do not treat fake actors as completion.
- [x] 0.2 Integrate the authenticated actor with Developer-owned shared API/routing and role services, preserving existing handler contracts and feature-owner file boundaries. Verify a real-session protected read and denial for missing, inactive and wrong-role accounts. Prioritize this team dependency before further isolated Maintainer UI work.

- [ ] 0.3 Establish the Maintainer-owned development Render/GitHub Actions environment early, with Developer-owner review and shared migration tooling. Verify a known revision deploys only after checks, configuration is environment-scoped, and the deployment host can reach the development database and agreed SoC endpoint without logging secrets. Record unavailable credentials/connectivity as blocked; reserve production release checks for 6.2.

## 1. Contracts and real read integration (MNT-P0 / remaining MNT-P1)

- [ ] 1.1 Review the proposed query/mutation/error/identity DTOs and remaining policies with their component owners, preserving confirmed CL-04/05. Verify a recorded contract decision and example fixtures for each seam before its real adapter is implemented; isolate fakes meanwhile.
- [x] 1.2 Integrate trusted identity, query/developer/history services and real shared-only checklist projections. Verify MT-01/02/03, malformed/nested response tests and absence of draft steps/revision in direct and embedded API responses; update role access instructions alongside integration.
- [x] 1.3 Establish the actual Java/database verification runner once that scaffold exists and connect the agreed checks to CI. Verify reproducible commands and disposable fixture setup; preserve the existing frontend `npm run verify` command without treating it as backend evidence.

## 2. Priority and assignment (MNT-P2)

- [x] 2.1 Implement explicit priority controls and the versioned operation against documented query/lifecycle seams, using fakes for isolated UI work. Verify T1/MT-04 and MT-13 input preservation; document the agreed priorities and conflict behavior.
- [x] 2.2 Implement assign/reassign controls and shared lifecycle adapter operations. Verify A1/MT-05/06 for active targets, eligible states, reasons, preserved history and former-assignee access loss; document role behavior and distinguish fake from real checks.
- [x] 2.3 Verify atomic updates, rollback, concurrent versions and actor/target deactivation against real authenticated APIs/PostgreSQL. Reproduce MT-07 and SEC-05/06, retain bounded race evidence, and keep the approved tests unchanged through repair.

## 3. Resolution review and discussion (MNT-P3)

- [x] 3.1 Implement explicit review, return, close and reopen actions against the lifecycle seam. Verify R1/MT-08/09/10 and recoverable UI conflicts; update lifecycle/reason instructions with the implementation.
- [x] 3.2 Integrate plain-text discussion through its owner's interface, without source/note/checklist edit ownership. Verify D1/MT-12/13, safe rendering and the agreed Closed-report comment policy; document limits and user behavior.
- [x] 3.3 Integrate source/proposal invalidation with the confirmed checklist contract. Verify MT-11/SEC-09: private checklist Save, unchanged Share and private Developer note save/clear preserve proposal/version and private notes are omitted from Maintainer reads; changed Share or other invalidating shared edit versus closure yields one atomic result in both PostgreSQL commit orders. Verify stale draft/report versions and empty Share preserve both copies; update integration documentation.

## 4. Advisory queue summary (MNT-P4)

- [x] 4.1 Implement authorized full-queue snapshot/context selection and the summary UI against a deterministic gateway fake. Verify S1/MT-14/15/22, bounded ordering, truncation, included/excluded IDs and zero-call empty queues; document selected context and proposed limits.
- [x] 4.2 Implement MNT-SUMMARY-1 instructions, exact output validation and safe errors/rendering. Verify S2/MT-16/17/23 and SEC-02/03 with malformed output, injection fixtures and no domain writes; document that structural safety does not prove live grounding.
- [x] 4.3 Implement full membership/version/assignment and access rechecks before delivery/reuse, keeping provider calls outside transactions. Verify MT-18/19 including excluded members, newly eligible reports, access revocation and cancellation-ignoring responses; document stale-result handling.
- [x] 4.4 Integrate shared durable budgets/deadlines/in-flight protection and matching-result reuse. Verify MT-20/21/22 across replicas, quota failure and surviving timed-out workers; document configured application limits separately from confirmed SoC quotas and provider caching.

## 5. Real integration and reflection evidence (MNT-P5)

- [ ] 5.1 Confirm SoC model/API/token accounting, quota and deployment-host connectivity with the gateway owner before enabling live generation. Verify sanitized connectivity and shared-budget evidence without committing secret values.
- [ ] 5.2 Run two legitimate Maintainer examples and bounded malicious-input trials with the real provider. Verify MT-24/SEC-04 through human grounding/completeness/usefulness review, recording unavailable/inconclusive outcomes separately; update AI instructions to match actual behavior.
- [ ] 5.3 Run frozen context-selection and reuse/invalidation comparisons from design.md. Verify fixtures, prompts/models, rubric, budgets, actual calls/latency/quality and exposed usage; add concrete results and measurement limitations to Reflections contributions.
- [x] 5.4 Coordinate shared DEV-SEC-01 through DEV-SEC-04 and DEV-WF-01 through DEV-WF-04 with the workflow owner. Verify isolated canary/scope/bypass/test-preservation and failed-check/recheck evidence, distinguish enforced denials from voluntary refusals, and expose no canary value.
- [x] 5.5 Contribute real coordination handoffs, defects, retries, context/model choices and available resource metrics. Verify isolated comparable starting states before claiming a single-agent versus multi-agent experiment; document incomplete measurements honestly.

## 6. Release verification (MNT-P6)

- [x] 6.1 Run the full cross-role browser/API flow against real services with separate role sessions. Verify MT-25 state/history/denial controls and independently reviewed candidate checks; record revision and separate controlled AI from live trials.
- [ ] 6.2 Implement team-managed Render deployment, GitHub Actions and environment settings as Maintainer owner, with Developer-owner review. Use the shared database/migration owner's tooling; configure distinct dev/production databases, users and secrets, run the repository verification command before deployment, and verify actual protection/approval settings, deployed commit and login/read/wrong-role smoke checks in both environments. Verify MT-26, document rollback and reconcile main/master submission targeting. Test a development deployment and host connectivity early; do not defer the first deployment until final release.
- [ ] 6.3 Contribute Maintainer, authentication and deployment material to UserGuide, DeveloperGuide and Reflections, including acknowledgements and verified interaction summaries. Review the coordinated documentation/evidence with the Reporter owner. Verify deployed behavior, workflow-file explanations, the separate GitHub Pages deliverable and actual submission readiness; do not claim guide assembly from the cancelled allocation.

## Workflow follow-up

- Archive only after implementation, verification and team review satisfy the change's tasks.
- Keep historical logs and commit/hash evidence; do not rewrite past results to match new gates.

## Current evidence follow-up

Recorded source c8e66d2 and CI results in logs/2026-11-09-maintainer-integration-checkpoint.md.
Added the unified verification entry point, explicit release scans and a reviewed
fix for deployment-hook isolation. Prepared and executed synthetic DEV-SEC/DEV-WF
fixtures and controlled coordination/reuse comparisons; see logs/security,
logs/agent-runs and logs/experiments for verdicts and measurement limits. Task 1.3
has reproducible Java/PostgreSQL setup plus CI execution. Kept broader tasks open
for remaining shared integration, owner review, live provider/host checks and
complete evaluation evidence; fixture passes alone do not satisfy those gates.

## Release reconciliation — 12 November

Marked implemented workflow and controlled-summary tasks complete using the final
follow-up in logs/2026-11-09-maintainer-integration-checkpoint.md, auth candidate
review, logs/maintainer/2026-11-09-main-integration.md, summary domain/UI evidence,
logs/socllm-port/verification.md and logs/socllm-per-task-limits/green.md. Synthetic
DEV-SEC/DEV-WF and coordination evidence support 5.4/5.5; they do not establish
computer-enforced restrictions or token savings.

Completed 6.1 locally through real APIs and browser screens; deployment remains 6.2.
Tasks 0.1/1.1 retain their explicit component-owner review criteria. Tasks 5.1–5.3
retain provider quota/host, human-quality/security and repeated-comparison gaps.
The real AI trials returned timeouts, rate limiting and one answer with a grounding
problem; have not marked live AI acceptance complete. Task 6.3 retains final guide
assembly/team review, Pages, deployed behavior and submission targeting. Updated
all three Maintainer contributions without claiming those shared deliverables.
