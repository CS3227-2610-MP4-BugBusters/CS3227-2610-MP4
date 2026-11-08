# Maintainer report browser Specification

## Purpose

Record the existing isolated read-only Maintainer frontend as an OpenSpec baseline.
Migrated QR-01 through QR-08 from MNT-READ-SPEC-1 after its implementation and review;
this migration is not a new feature implementation or a retrospective Red baseline.
Real authentication, Java endpoints, persistence, mutations and AI remain outside
the implemented frontend. The HTTP DTOs remain proposed integration contracts.

## Requirements

### Requirement: MNT-READ-01 Read-only report browsing

The frontend SHALL display report summaries and selected report details through
an injected query adapter. Details SHALL include source fields, investigation
notes, checklist, responses, discussion, current proposal and history as read-only
text. It SHALL expose no mutation or AI controls in this increment.

#### Scenario: Inspect a report

- **GIVEN** the adapter has established an active Maintainer and returned a valid report
- **WHEN** that report is selected
- **THEN** the frontend displays its read-only sections without a mutation or AI request

### Requirement: MNT-READ-02 Filtering and pagination

The query seam SHALL intersect status, priority and assignee filters, default to
50 results, cap the requested limit at 100, and use nonnegative integer offsets.
Results SHALL be ordered by updated instant descending then ID ascending.
Filter changes SHALL reset pagination and selection; page changes SHALL clear selection.

#### Scenario: Filter and navigate

- **WHEN** filters change or another page is requested
- **THEN** the current filter intersection and total determine the displayed page
- **AND** obsolete selections are cleared and navigation stays within page bounds

#### Scenario: Equivalent and fractional timestamps

- **GIVEN** accepted UTC timestamps include fractional seconds or equivalent textual forms
- **WHEN** the synthetic adapter orders results
- **THEN** numeric instants determine chronology and equal instants use the ID tie-break

### Requirement: MNT-READ-03 Bounded HTTP query boundary

The HTTP adapter SHALL use same-origin GET requests with credentials, no-store,
an Accept JSON header and caller cancellation. It SHALL validate filters before
transport and parse nested DTOs before display. Responses SHALL be bounded to
2,000,000 UTF-8 bytes while streaming, page items to 100, detail/developer
collections to 500, and general text to 20,000 UTF-16 code units. Invalid payloads
SHALL produce fixed safe errors rather than expose raw upstream bodies.

#### Scenario: Reject invalid input or response

- **WHEN** filters are invalid or a response exceeds its bounds or nested shape
- **THEN** invalid filters make no transport request and invalid responses are not rendered
- **AND** the displayed error contains no upstream body or stack trace

### Requirement: MNT-READ-04 Access and asynchronous state

The frontend SHALL establish an active Maintainer before protected reads and
recheck identity on refresh or adapter replacement. A current-session 401 or 403
SHALL clear protected data and invalidate pending results. Obsolete responses
SHALL NOT overwrite current identity, filter, page or selection state, even when
an adapter ignores cancellation. These UI guards SHALL NOT be treated as proof
of server-side authorization.

#### Scenario: Access is denied during a pending request

- **GIVEN** protected data is displayed and another request is pending
- **WHEN** a protected operation denies the current session
- **THEN** protected content is cleared and a late success cannot restore it

#### Scenario: Replace a query adapter

- **WHEN** the query adapter changes while the frontend was previously authorized
- **THEN** identity is checked again before the new adapter receives protected reads

### Requirement: MNT-READ-05 Safe and distinct display states

The frontend SHALL distinguish access checking, list/detail loading, empty results,
safe failure/retry and missing-detail states. Source content SHALL render as escaped
text without interpreted HTML or Markdown. Filters and buttons SHALL have accessible
names, and the layout SHALL support narrow mobile viewports without horizontal overflow.

#### Scenario: Show source markup as text

- **WHEN** a report contains markup-like source text
- **THEN** the literal text is displayed without creating an injected element

### Requirement: MNT-READ-06 Synthetic development boundary

Normal startup and production builds SHALL select HTTP. Synthetic identity,
developers, reports and investigation data SHALL be selectable only through the
explicit development mode with a visible synthetic-data label. Production SHALL
NOT automatically fall back to fixtures when a service fails.

#### Scenario: Missing production session

- **WHEN** the production HTTP identity request returns 401
- **THEN** the sign-in message appears with no protected request or synthetic fallback

## Traceability and integration limits

Trace MNT-READ-01 to QR-01; MNT-READ-02 to QR-02; MNT-READ-03 to QR-03;
MNT-READ-04 to QR-04/06; MNT-READ-05 to QR-05/07; MNT-READ-06 to QR-08.
Existing tests are in `src/frontend/maintainer/src/App.test.tsx` and `query.test.ts`.
Execution and independent-review evidence remains in
`logs/maintainer/2026-11-07-read-only-increment.md`.

The active `share-investigation-checklist` change owns CL-04 shared-only checklist
visibility and CL-05 private Save versus published Share semantics. Do not infer
their real API, lifecycle or database implementation from this frontend baseline.
Future role requirements, technical decisions, tasks and MT-01 through MT-26 now
live in `openspec/changes/complete-maintainer-role/`. The historical Maintainer
workflow documents have been superseded. Follow the current agent/TDD gates and
the confirmed checklist contract; do not infer completed backend behavior.

## Query contract and QR acceptance detail

Migrated the remaining MNT-READ-SPEC-1 contract details here so no live specification
depends on the deleted workflow files. The proposed HTTP seam remains distinct
from confirmed shared-server integration.

### Proposed query seam

Expose `getCurrentUser(signal)`, `listReports(filters, signal)`, `listDevelopers(signal)` and `getReport(id, signal)`. Each returns parsed domain values or a bounded typed error. Support AbortSignal for every operation. Do not derive identity from query parameters, caller-supplied role fields or report authors.

| Request | Proposed response |
| --- | --- |
| `GET /api/auth/me` | `{id,displayName,role,active}`; role is `MAINTAINER`, `DEVELOPER` or `REPORTER`, active is boolean |
| `GET /api/maintainer/reports` | `{items: ReportSummary[], total}` |
| `GET /api/maintainer/developers` | Active Developer summaries `{id,displayName}[]` |
| `GET /api/maintainer/reports/{id}` | `ReportDetail` |

Use UUID identifiers and ISO-8601 timestamps. ReportSummary={id,title,status,priority,reporter:{id,displayName},assignee:null-or-{id,displayName},version,createdAt,updatedAt}; ReportDetail extends it with the source and nested fields below. Use statuses `Submitted`, `Assigned`, `In progress`, `Resolution proposed`, `Closed`; priorities `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. Keep nullable assignee and nonnegative integer version explicit.

Propose these detail shapes so a future adapter can map agreed shared DTOs without spreading uncertainty through components:

- Source: `description`, `reproductionSteps`, `expectedBehavior`, `actualBehavior`, `environment` as strings.
- `notes: {id,author:{id,displayName},text,createdAt}[]`.
- `checklist: {id,text,completed:boolean}[]`.
- `responses: {id,author:{id,displayName},text,createdAt}[]`.
- `comments: {id,author:{id,displayName},text,createdAt}[]`.
- `currentProposal: null | {id,version,text,author:{id,displayName},createdAt}`.
- `history: {id,type,text,actor:{id,displayName},createdAt}[]`, including assignment and status history.

Treat these as a local proposed wire contract, not evidence that another role already supplies it. Parse all HTTP response payloads at runtime, including nested values and current-user data. Reject missing, malformed or oversized structures with a safe contract error; never render partially trusted malformed payloads. Bound collection/text sizes consistently with fixture needs and document parser limits in the package.

### HTTP and fake adapters

Use same-origin relative paths, GET only, `credentials: 'same-origin'`, `cache: 'no-store'`, an Accept JSON header and caller cancellation. Encode IDs and query values. Send no actor, role or request body. Validate filters before transport: optional known status/priority, UUID assigneeId, integer offset >= 0, integer limit 1–100 (default 50). Combine filters by intersection; order `updatedAt` descending then ID ascending. Reject unsupported filter fields.

Map 401 to session-required, 403 to access-denied, 404 detail to report-missing, invalid payloads to safe contract failure, and network/other failures to retryable generic failure where appropriate. Do not display raw server bodies, stack traces, request URLs or provider text. Suppress aborted requests as ordinary cancellation. Use local fixed messages for errors.

Regular `npm start` uses HTTP. `npm run demo` explicitly enables a development-only synthetic adapter and shows a visible synthetic-data label. Permit fake selection only with the development build flag; production builds must not fall back to synthetic data when HTTP fails or configuration is missing. Keep fixtures/fake loading outside the production entry dependency path where practical. Tests may inject controlled responses; add no product debug/scenario controls.

Synthetic fixtures cover all lifecycle states/priorities, unassigned and assigned reports, two or more Developers, equal updatedAt values and more than 50 rows. Include rich detail sections and markup-like strings. Fake sorting, filtering and pagination follow the same contract, with no writes. Simulate empty/error/denial/race cases through test injection.

### UI state and concurrency

Start with an identity check and show no protected content before an active Maintainer is established. Block inactive or wrong-role users without report requests. Refresh rechecks identity before refetching protected data. A 401/403 from any protected operation clears list/detail data, aborts other work and invalidates pending results. Do not retain protected rows underneath a denial message.

Changing filters resets offset and selection, immediately clears stale rows/details and cancels previous requests. Page changes clear selection/details. Show matching total and page navigation without crossing bounds. Selecting a report clears the previous detail immediately. Sequence requests or associate them with an identity/filter/selection generation so obsolete responses cannot repopulate content, even if an adapter ignores cancellation. Retry only the current failed operation and retain current filters. Distinguish initial loading, list empty, list failure, detail loading, detail missing, detail failure and access/session denial.

Render every source, author, comment, proposal and history value as escaped text. Use no raw HTML or interpreted Markdown; never construct navigation from untrusted generated links. Provide labelled filters, semantic buttons and headings, and readable status announcements. Show no editing or AI action controls in this increment.

### Acceptance and verification

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

The existing package verification command is `npm run verify`; record new execution results separately from this oracle. Use deterministic adapter and component tests for QR-01–08, including request construction, payload validation and deferred race tests. A browser smoke check verifies the rendered demo and responsive layout where available. Independent verification reports PASS/FAIL/PARTIAL by ID; backend authorization, PostgreSQL and real shared-service integration remain explicitly pending.
