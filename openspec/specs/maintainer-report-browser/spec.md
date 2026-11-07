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
Legacy full-role planning under `workflow/maintainer/` is historical context, not
an override of the approved checklist decisions. Future behavior changes belong in
new OpenSpec deltas and follow the current agent/TDD gates.
