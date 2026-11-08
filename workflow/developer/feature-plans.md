# Developer feature plans

This is the Developer owner's working plan. The [Developer journey decisions](developer-journey-decisions.md) record agreed behavior and outstanding shared decisions. Each feature is a separate workstream with its own scope and completion checks. The order below is a recommendation, not a claim that the underlying shared application already exists. Use the linked OpenSpec change as the source of accepted behavior; features without an OpenSpec change need one before implementation.

## Shared prerequisite: application test and integration foundation

- **Purpose:** Give feature tests a real application target, trusted actor seam and disposable shared-shaped database fixture. Keep synthetic identities in test code.
- **Plan:** Establish the server/router and session interface; provide a disposable fixture and reproducible runner; demonstrate authentication and fixture controls before running behavior tests. Reconcile actual shared schema, migrations and sessions when available.
- **Done when:** The runner reaches the application handler, session and database controls pass, and setup can be recreated safely. Local synthetic verification and real integrated verification are recorded separately.
- **Current state:** The shared app route/session adapter is absent. A [draft API suite](../../src/backend/developer/test/assigned_reports_api_test.py) and fixture exist, but have not been independently verified or run against an application. The fixture resets a table; use only a verified disposable database.

## Feature 1: Assigned report list

See the [focused feature plan](features/assigned-report-list.md) for acceptance cases, security concerns, verification and evidence.

- **Scope:** Return the signed-in active Developer's currently assigned reports with ID, title and status. No report detail, search or UI layout in this increment.
- **Dependencies:** Shared prerequisite and [list contract](assigned-reports-api-contract.md). Use the [OpenSpec change](../../openspec/changes/developer-assigned-reports/proposal.md).
- **Plan:** Design focused tests; independently verify the baseline; implement the authorized list; independently verify it and review authorization. Use synthetic D1 and D2 and reassign a report.
- **Done when:** D1 and D2 receive only their current assignments before and after reassignment; anonymous, inactive and wrong-role requests are denied; responses omit unrelated report fields. Record local and integrated results separately.
- **Current state:** The local handler and AL-01–AL-08 passed independent checks with synthetic sessions and disposable PostgreSQL; shared authentication, routing and schema integration remain open. The missing-route baseline was not a verified behavioral Red.

## Feature 2: Search assigned reports

- **Scope:** Literal, case-insensitive title substring or complete report-ID search over the authorized set, as defined in the list contract.
- **Dependencies:** Feature 1 and the same OpenSpec change.
- **Plan:** Test title, complete ID, whitespace, literal wildcard characters and another Developer's matching report; implement; independently verify.
- **Done when:** Search matches the contract and never returns an unassigned report, including after reassignment.
- **Current state:** Local synthetic-session search and independent authorization, Unicode and existing list regressions passed. Real shared integration remains open. See the [search evidence](../../logs/developer/2026-11-08-assigned-report-search.md).

## Feature 3: Status filter

- **Scope:** Filter the authorized list by one supported status; combine search and status using AND.
- **Dependencies:** Feature 1; combined search cases also depend on Feature 2.
- **Plan:** Test each status, invalid values and combined filters; implement; independently verify.
- **Done when:** Results contain only matching assignments and invalid statuses produce the specified safe error.
- **Current state:** Local status, combined-search, authorization and regression checks passed with synthetic sessions and disposable PostgreSQL; integrated checks remain open. See the [context and resource evidence](../../logs/developer/2026-11-08-status-filter-context-resource.md).

## Feature 4: Pagination and list validation

- **Scope:** Deterministic ID ordering, page and page-size defaults and bounds, filtered totals, and rejection of invalid, duplicate or unknown parameters.
- **Dependencies:** Feature 1; cover search/filter combinations after Features 2 and 3.
- **Plan:** Test ordering, page boundaries, consistent count and items, out-of-range pages and parameter validation; implement; independently verify.
- **Done when:** Responses follow the contract and totals and pages never reveal other Developers' reports.
- **Current state:** Local pagination and security checks passed with synthetic sessions and disposable PostgreSQL; real shared-app integration remains open. See [pagination evidence](../../logs/developer/2026-11-08-pagination.md).

## Feature 5: Developer dashboard presentation

- **Scope:** Display assigned report statuses, list, search and filters with appropriate loading, error, no-assignment, no-match and out-of-range states. Dashboard notifications are a separate feature in the journey decisions.
- **Dependencies:** Features 1–4, a shared dashboard template and the agreed frontend/API integration. Template and visual decisions remain pending.
- **Plan:** Apply shared components; connect the real API; test normal and error/empty states and responsive behavior under the team's template.
- **Done when:** A Developer can use the list and filters, states are distinguishable, and frontend and integration checks pass. The existing checklist demo remains a synthetic preview.

## Feature 6: Assigned report detail

- **Scope:** Define and display the information a currently assigned Developer needs to investigate a report.
- **Dependencies:** Shared report model, access rules and report detail API; checklist integration follows separately.
- **Plan:** First create an OpenSpec change defining fields, reassignment behavior and direct API permissions. Then test, implement, independently verify and connect the shared detail template.
- **Done when:** The current assignee can open the permitted detail; another or former assignee is denied, including direct requests. The exact field list remains a decision for the feature spec.

## Feature 7: Checklist private Save

- **Scope:** Save a private checklist draft with separate draft revision and preserve the shared copy and pending resolution.
- **Dependencies:** [Checklist OpenSpec](../../openspec/changes/share-investigation-checklist/proposal.md) and [storage contract](checklist-storage-contract.md).
- **Plan:** Connect existing local Save code to the real authenticated report detail and shared schema; run integrated current/stale revision, role and reassignment cases.
- **Done when:** All applicable Save checks pass against real sessions and persistence; local-only results are not counted as integration.
- **Current state:** Local HTTP and PostgreSQL tests cover current/stale revisions and former-assignee Save with synthetic sessions. Integration remains open.

## Feature 8: Share checklist

- **Scope:** Publish the persisted non-empty draft to the Reporter and Maintainer, retaining the last published copy after later private saves.
- **Dependencies:** Feature 7's shared report/session foundation and checklist OpenSpec; resolution and closure rules depend on shared lifecycle integration.
- **Plan:** Independently design and verify Share tests, implement authorized atomic publication, connect shared-only readers, and verify empty Share, reassignment, stale revisions and closure races.
- **Done when:** The OpenSpec's Save/Share, permissions and lifecycle/race checks pass against the integrated application.

## Feature 9: Investigation lifecycle and resolution proposal

- **Scope:** Start an assigned investigation and propose a resolution; the Maintainer reviews and closes or returns it.
- **Dependencies:** Shared lifecycle contract, report versioning, authentication and the Maintainer's implementation. Exact Developer-facing API and fields need an OpenSpec change or agreed existing spec before implementation.
- **Plan:** Specify authorized transitions and stale-write behavior; test, implement the Developer-owned portions and independently verify with Maintainer integration.
- **Done when:** Allowed transitions succeed, wrong actors and stale writes fail, and the shared lifecycle remains consistent.

## Feature 10: Developer AI assistance

- **Scope:** Draft investigation checklist steps and, if confirmed in the agreed release scope, assess Reporter responses. AI results are advisory and require Developer review.
- **Dependencies:** Checklist and response contracts, shared AI gateway, versioned inputs and agreed quota/security evaluation. Confirm assessment scope before implementing it.
- **Plan:** Specify each AI capability separately; test with deterministic provider fixtures, including stale results and prompt injection; implement and independently review; run bounded live evaluation where available.
- **Done when:** Approved results can be reviewed safely, stale or unauthorized results are handled, and security and usefulness evidence is recorded without inventing token measurements.

## Execution and evidence for each feature

Use the repository [TDD workflow](../tdd.md): focused test design, independent baseline verification, implementation, independent candidate verification, and separate security review for authorization or AI security. The feature owner has requested consolidated execution, so avoid unnecessary routine handoffs; follow any explicit workflow gate and surface real decisions or blockers. Record actual model/effort, checks, revisions, outcomes, unavailable token measurements and integration limits in `logs/developer/`. A feature is complete only when its own checks pass; local synthetic success does not complete a real integration task.
