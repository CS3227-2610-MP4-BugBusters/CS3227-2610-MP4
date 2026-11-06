# Maintainer read-only increment

Status: proposed MNT-READ-SPEC-1, 7 November 2026. Scope MNT-P1 against MNT-READ-01/Q1 and the frontend portions of MT-01/02/03/13. Shared contracts remain proposals; no backend implementation or integration approval claimed.

## Boundary

Implement a standalone React and TypeScript feature package under `src/frontend/maintainer/`, with minimal local development wiring and executable checks. Keep package configuration, sources, fixtures and tests inside that directory. Consume an injected query interface; do not scaffold the shared application or assign shared ownership. Exclude mutations, AI, authentication implementation, databases and deployment.

Render the queue, intersecting filters, pagination and selected report details. Read source and investigation content without editing it. The UI role guard prevents accidental display; server authorization on every real endpoint remains necessary and cannot be proven by a fake or component test.

## Proposed query seam

Expose `getCurrentUser(signal)`, `listReports(filters, signal)`, `listDevelopers(signal)` and `getReport(id, signal)`. Each returns parsed domain values or a bounded typed error. Support AbortSignal for every operation. Do not derive identity from query parameters, caller-supplied role fields or report authors.

| Request | Proposed response |
| --- | --- |
| `GET /api/auth/me` | `{id,displayName,role,active}`; role is `MAINTAINER`, `DEVELOPER` or `REPORTER`, active is boolean |
| `GET /api/maintainer/reports` | `{items: ReportSummary[], total}` |
| `GET /api/maintainer/developers` | Active Developer summaries `{id,displayName}[]` |
| `GET /api/maintainer/reports/{id}` | `ReportDetail` |

Use UUID identifiers, ISO-8601 timestamps and the report summary shape in [technical specification](TECHNICAL-SPEC.md). Use statuses `Submitted`, `Assigned`, `In progress`, `Resolution proposed`, `Closed`; priorities `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. Keep nullable assignee and nonnegative integer version explicit.

Propose these detail shapes so a future adapter can map agreed shared DTOs without spreading uncertainty through components:

- Source: `description`, `reproductionSteps`, `expectedBehavior`, `actualBehavior`, `environment` as strings.
- `notes: {id,author:{id,displayName},text,createdAt}[]`.
- `checklist: {id,text,completed:boolean}[]`.
- `responses: {id,author:{id,displayName},text,createdAt}[]`.
- `comments: {id,author:{id,displayName},text,createdAt}[]`.
- `currentProposal: null | {id,version,text,author:{id,displayName},createdAt}`.
- `history: {id,type,text,actor:{id,displayName},createdAt}[]`, including assignment and status history.

Treat these as a local proposed wire contract, not evidence that another role already supplies it. Parse all HTTP response payloads at runtime, including nested values and current-user data. Reject missing, malformed or oversized structures with a safe contract error; never render partially trusted malformed payloads. Bound collection/text sizes consistently with fixture needs and document parser limits in the package.

## HTTP and fake adapters

Use same-origin relative paths, GET only, `credentials: 'same-origin'`, `cache: 'no-store'`, an Accept JSON header and caller cancellation. Encode IDs and query values. Send no actor, role or request body. Validate filters before transport: optional known status/priority, UUID assigneeId, integer offset >= 0, integer limit 1–100 (default 50). Combine filters by intersection; order `updatedAt` descending then ID ascending. Reject unsupported filter fields.

Map 401 to session-required, 403 to access-denied, 404 detail to report-missing, invalid payloads to safe contract failure, and network/other failures to retryable generic failure where appropriate. Do not display raw server bodies, stack traces, request URLs or provider text. Suppress aborted requests as ordinary cancellation. Use local fixed messages for errors.

Regular `npm start` uses HTTP. `npm run demo` explicitly enables a development-only synthetic adapter and shows a visible synthetic-data label. Permit fake selection only with the development build flag; production builds must not fall back to synthetic data when HTTP fails or configuration is missing. Keep fixtures/fake loading outside the production entry dependency path where practical. Tests may inject controlled responses; add no product debug/scenario controls.

Synthetic fixtures cover all lifecycle states/priorities, unassigned and assigned reports, two or more Developers, equal updatedAt values and more than 50 rows. Include rich detail sections and markup-like strings. Fake sorting, filtering and pagination follow the same contract, with no writes. Simulate empty/error/denial/race cases through test injection.

## UI state and concurrency

Start with an identity check and show no protected content before an active Maintainer is established. Block inactive or wrong-role users without report requests. Refresh rechecks identity before refetching protected data. A 401/403 from any protected operation clears list/detail data, aborts other work and invalidates pending results. Do not retain protected rows underneath a denial message.

Changing filters resets offset and selection, immediately clears stale rows/details and cancels previous requests. Page changes clear selection/details. Show matching total and page navigation without crossing bounds. Selecting a report clears the previous detail immediately. Sequence requests or associate them with an identity/filter/selection generation so obsolete responses cannot repopulate content, even if an adapter ignores cancellation. Retry only the current failed operation and retain current filters. Distinguish initial loading, list empty, list failure, detail loading, detail missing, detail failure and access/session denial.

Render every source, author, comment, proposal and history value as escaped text. Use no raw HTML or interpreted Markdown; never construct navigation from untrusted generated links. Provide labelled filters, semantic buttons and headings, and readable status announcements. Show no editing or AI action controls in this increment.

## Acceptance and verification

| ID | Required observable evidence |
| --- | --- |
| QR-01 | Active-Maintainer queue and detail render all specified read-only sections; no mutation or AI requests. Trace Q1/MT-01. |
| QR-02 | Every filter and combined intersection, stable tie ordering, default 50, cap 100, offset and unmatched queue produce correct results. Trace Q1/MT-01/03. |
| QR-03 | HTTP requests use exact GET paths/query/session/cache/cancellation rules; malformed filters fail before fetch, malformed nested DTOs fail safely. Trace MT-03. |
| QR-04 | Initial inactive/wrong-role/unauthenticated identity prevents protected reads; protected 401/403 clears existing data and blocks late results; refresh rechecks identity. Trace MT-02/13. This verifies UI handling only. |
| QR-05 | Loading, empty, network failure/retry, detail missing and safe errors remain distinct; no raw upstream body appears. Trace MT-03/13. |
| QR-06 | Deferred responses delivered out of order after filter, selection, refresh and access changes cannot overwrite current state, including cancellation-ignoring adapters. Trace MT-13. |
| QR-07 | Markup-like fixture content renders as text with no script, injected element or interpreted link execution; controls have accessible names. Trace Q1/MT-13. |
| QR-08 | Default startup selects HTTP; demo is visibly synthetic and gated to development; production has no automatic fake fallback. Build and adapter-wiring checks support the boundary. |

Establish executable package typecheck/build/tests after selecting tooling; record actual commands and results separately from this oracle. Use deterministic adapter and component tests for QR-01–08, including request construction, payload validation and deferred race tests. A browser smoke check verifies the rendered demo and responsive layout where available. Independent verification reports PASS/FAIL/PARTIAL by ID; backend authorization, PostgreSQL and real shared-service integration remain explicitly pending.
