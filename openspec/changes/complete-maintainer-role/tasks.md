# Tasks

Status: migrated future work, not implementation results. All tasks below remain
unchecked. The existing fake-backed read-only frontend is recorded in the main
`maintainer-report-browser` spec and its historical logs; do not recreate its
completion as a newly approved Red baseline.

For each behavior task, follow `workflow/tdd.md`: test_designer, fresh
baseline_verifier, feature-owner approval of the verified Red, implementer,
fresh candidate_verifier and separate security_reviewer for authorization/AI.
Record actual fixture/runner/spec and candidate references, commands and outcomes.
An unavailable contract, fixture or runner is BLOCKED, not a passing Red.

## 1. Contracts and real read integration (MNT-P0 / remaining MNT-P1)

- [ ] 1.1 Review the proposed query/mutation/error/identity DTOs and remaining policies with their component owners, preserving confirmed CL-04/05. Verify a recorded contract decision and example fixtures for each seam before its real adapter is implemented; isolate fakes meanwhile.
- [ ] 1.2 Integrate trusted identity, query/developer/history services and real shared-only checklist projections. Verify MT-01/02/03, malformed/nested response tests and absence of draft steps/revision in direct and embedded API responses; update role access instructions alongside integration.
- [ ] 1.3 Establish the actual Java/database verification runner once that scaffold exists and connect the agreed checks to CI. Verify reproducible commands and disposable fixture setup; preserve the existing frontend `npm run verify` command without treating it as backend evidence.

## 2. Priority and assignment (MNT-P2)

- [ ] 2.1 Implement explicit priority controls and the versioned operation against documented query/lifecycle seams, using fakes for isolated UI work. Verify T1/MT-04 and MT-13 input preservation; document the agreed priorities and conflict behavior.
- [ ] 2.2 Implement assign/reassign controls and shared lifecycle adapter operations. Verify A1/MT-05/06 for active targets, eligible states, reasons, preserved history and former-assignee access loss; document role behavior and distinguish fake from real checks.
- [ ] 2.3 Verify atomic updates, rollback, concurrent versions and actor/target deactivation against real authenticated APIs/PostgreSQL. Reproduce MT-07 and SEC-05/06, retain bounded race evidence, and keep the approved tests unchanged through repair.

## 3. Resolution review and discussion (MNT-P3)

- [ ] 3.1 Implement explicit review, return, close and reopen actions against the lifecycle seam. Verify R1/MT-08/09/10 and recoverable UI conflicts; update lifecycle/reason instructions with the implementation.
- [ ] 3.2 Integrate plain-text discussion through its owner's interface, without source/note/checklist edit ownership. Verify D1/MT-12/13, safe rendering and the agreed Closed-report comment policy; document limits and user behavior.
- [ ] 3.3 Integrate source/proposal invalidation with the confirmed checklist contract. Verify MT-11/SEC-09: private Save and unchanged Share preserve proposal/version; changed Share or other invalidating edit versus closure yields one atomic result in both PostgreSQL commit orders. Verify stale draft/report versions and empty Share preserve both copies; update integration documentation.

## 4. Advisory queue summary (MNT-P4)

- [ ] 4.1 Implement authorized full-queue snapshot/context selection and the summary UI against a deterministic gateway fake. Verify S1/MT-14/15/22, bounded ordering, truncation, included/excluded IDs and zero-call empty queues; document selected context and proposed limits.
- [ ] 4.2 Implement MNT-SUMMARY-1 instructions, exact output validation and safe errors/rendering. Verify S2/MT-16/17/23 and SEC-02/03 with malformed output, injection fixtures and no domain writes; document that structural safety does not prove live grounding.
- [ ] 4.3 Implement full membership/version/assignment and access rechecks before delivery/reuse, keeping provider calls outside transactions. Verify MT-18/19 including excluded members, newly eligible reports, access revocation and cancellation-ignoring responses; document stale-result handling.
- [ ] 4.4 Integrate shared durable budgets/deadlines/in-flight protection and matching-result reuse. Verify MT-20/21/22 across replicas, quota failure and surviving timed-out workers; document configured application limits separately from confirmed SoC quotas and provider caching.

## 5. Real integration and reflection evidence (MNT-P5)

- [ ] 5.1 Confirm SoC model/API/token accounting, quota and deployment-host connectivity with the gateway owner before enabling live generation. Verify sanitized connectivity and shared-budget evidence without committing secret values.
- [ ] 5.2 Run two legitimate Maintainer examples and bounded malicious-input trials with the real provider. Verify MT-24/SEC-04 through human grounding/completeness/usefulness review, recording unavailable/inconclusive outcomes separately; update AI instructions to match actual behavior.
- [ ] 5.3 Run frozen context-selection and reuse/invalidation comparisons from design.md. Verify fixtures, prompts/models, rubric, budgets, actual calls/latency/quality and exposed usage; add concrete results and measurement limitations to Reflections contributions.
- [ ] 5.4 Coordinate shared DEV-SEC-01 through DEV-SEC-04 and DEV-WF-01 through DEV-WF-04 with the workflow owner. Verify isolated canary/scope/bypass/test-preservation and failed-check/recheck evidence, distinguish enforced denials from voluntary refusals, and expose no canary value.
- [ ] 5.5 Contribute real coordination handoffs, defects, retries, context/model choices and available resource metrics. Verify isolated comparable starting states before claiming a single-agent versus multi-agent experiment; document incomplete measurements honestly.

## 6. Release verification (MNT-P6)

- [ ] 6.1 Run the full cross-role browser/API flow against real services with separate role sessions. Verify MT-25 state/history/denial controls and independently reviewed candidate checks; record revision and separate controlled AI from live trials.
- [ ] 6.2 Coordinate assigned release owners to verify separate dev/production databases, users, secrets/configuration, actual deployment protection, deployed commit and login/read/wrong-role smoke checks. Verify MT-26, reconcile main/master submission targeting and retain sanitized evidence; do not infer shared ownership from the old allocation.
- [ ] 6.3 Complete release-specific UserGuide, DeveloperGuide and Reflections contributions, acknowledgements and verified interaction summaries. Verify documentation matches deployed behavior, workflow files are explained, shared assembly has an owner and GitHub Pages remains a distinct team deliverable.

## Workflow follow-up

- Archive only after implementation, verification and team review satisfy the change's tasks.
- Keep historical logs and commit/hash evidence; do not rewrite past results to match new gates.
