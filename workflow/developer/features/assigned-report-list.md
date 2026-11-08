# Feature plan: assigned report list (DR-01)

## Outcome and scope

An active Developer can request the first page of reports currently assigned to them. Each item contains the report ID, title and status. This feature delivers the basic unfiltered API list; the dashboard layout, report detail, search and status filters are separate features. The first response follows the contract's default page and page size. Full pagination controls and query validation are addressed in Feature 4; do not claim those later cases are complete from the first slice.

Sources of behavior: [DR-01 OpenSpec](../../../openspec/changes/developer-assigned-reports/specs/developer-assigned-reports/spec.md) and the [backend contract](../assigned-reports-api-contract.md). The contract also describes later list features; those remain separate workstreams. If this plan conflicts with the spec or contract, resolve the conflict before implementing affected behavior.

## Acceptance cases

Use a disposable database containing D1 and D2, a Reporter, an inactive Developer, and reports with distinct IDs, titles and statuses. Verify stored state and resolved actor before testing. The first slice uses the default unfiltered request; its results must be ordered and bounded as specified in the contract.

| ID | Setup and action | Expected result |
| --- | --- | --- |
| AL-01 | D1 requests the list with reports assigned to D1, D2, and nobody. | HTTP 200; only D1's current assignments appear, with exactly ID, title and status. The total counts only D1's assignments. |
| AL-02 | An active Developer with no assignments requests the list. | HTTP 200 with empty items and zero total, using the normal response envelope. |
| AL-03 | D1 has a closed report still assigned to D1. | The report is included when it falls within the returned page. |
| AL-04 | Reassign report R from D1 to D2 and commit; each requests again. | R is absent from D1's eligible results and present in D2's, subject to pagination; totals reflect the new assignment. |
| AL-05 | Request without a valid session. | HTTP 401 with a safe error envelope and no report data. |
| AL-06 | A Reporter, Maintainer or inactive Developer requests the list. | HTTP 403 with a safe error envelope and no report data. |
| AL-07 | D1 attempts to supply another Developer's identity through a query parameter. | The server rejects the unknown parameter with HTTP 400 `VALIDATION_FAILED`; no other user's data is returned. The trusted session remains the identity source. |
| AL-08 | A Developer has more than the default page size of assigned reports. | The first page contains at most the default page size, ordered by ID; total counts all their assignments. No other user's report affects the count. |

The dashboard's visible empty-state wording is part of Feature 5, not acceptance of this API-only slice. Tests should use enough distinct data to detect cross-user leakage and check the full response, not only the number of items.

## Implementation sequence and checks

1. **Prepare the target:** identify the actual application handler/router and trusted-actor boundary; establish a reproducible disposable database and session controls. The fixture must never reset a real or shared database. Existing synthetic tests and the reference adapter are test aids, not the application.
2. **Design tests:** test designer writes focused AL-01–AL-08 checks against the application target, recording command, fixture, revision and evidence. Review existing draft suite before reusing it; it has not yet been independently verified. If a later feature's tests are in the suite, run a clearly selected first-slice subset.
3. **Verify the baseline:** a fresh verifier checks tests against the spec and contract, reproduces the intended failure and confirms setup controls. A missing server, fixture or unmounted route is a setup failure unless the workflow's requirements for a valid behavioral Red are actually met. Do not force a failing test.
4. **Implement and verify:** follow the [TDD workflow](../../tdd.md), preserving the reviewed tests. The implementer builds the smallest list behavior; a fresh verifier reruns the selected suite and checks its assertions. An independent security reviewer checks the authorization boundary and leakage cases.
5. **Integrate:** connect the shared authentication, report migration and application routing when available. Rerun the same essential acceptance cases using real sessions and the shared database. Only mark integrated checks complete when they pass.

## What to look out for

- The actor comes from trusted server-side authentication. A client-supplied ID must not select another Developer's reports.
- Filter by current assignment in the data query before counting or limiting results. Totals, pages, errors and logs must not disclose other users' reports or private checklist fields.
- Reassignment must be committed before checking the next request. Previously delivered data cannot be recalled; do not claim otherwise.
- Keep count and items consistent within a response as required by the contract. Do not mistake an empty page beyond the end for zero assignments.
- Synthetic actor and database adapters must remain test-only. A passing reference adapter or a route-absence probe is not evidence that the application passes.
- Shared session, actual report schema and the standardized UI are not available in this checkout. Keep those integration checks open; avoid creating a second production report store.

## Evidence and completion

Record the exact selected tests and commands, starting revision and relevant uncommitted file hashes, fixture/runner setup, actual agent roles and model settings, findings, corrections, and independently reproduced outcomes in `logs/developer/`. Record unavailable token measurements as unavailable. Keep local synthetic and integrated results separate.

**Local slice complete:** AL-01–AL-08 pass against the local application handler, independently verified with a separate authorization review. **Fully integrated:** the same applicable cases pass with real sessions, shared routing and persisted assignments, and the relevant OpenSpec tasks' full checks pass. A local pass alone does not complete the broader OpenSpec tasks.

**Current status:** The local handler passed AL-01–AL-08 against synthetic sessions and disposable PostgreSQL, followed by independent candidate and security reviews. The route-absence baseline was not a verified behavioral Red. See the [local evidence](../../../logs/developer/2026-11-08-assigned-report-list-local.md). Real authentication, shared routing and schema integration remain open.
