# Proposal

## Why

Reporters need to file, list and read their own reports. Reproduction steps, logs
and environment details are easier to read with GitHub-style formatting such as
lists, code blocks and tables. The shared `reports` table (SR-01) stores only title
and description, and no Reporter API exists. The Maintainer browser currently
shows every source field as escaped literal text (MNT-READ-05), so any formatting
a Reporter writes would appear as raw `##` and `*` characters to the people
triaging and investigating the bug.

## What Changes

- **RPT-01..04** Reporter backend: create a report, list own reports, and read one
  own report, with server-side ownership checks, validation, the shared error
  envelope and no client-supplied identity. Edits are out of scope.
- Additive Reporter-owned migration for the detail fields `reproduction_steps`,
  `expected_behavior`, `actual_behavior` and `environment`. The Maintainer DTO
  already expects these fields.
- Store every Reporter-authored long-text field as **Markdown source exactly as
  typed**. The server never renders, converts or stores HTML.
- **MDR-01..05** A shared safe Markdown capability: a GitHub-flavoured subset
  renderer and a GitHub-style **Write | Preview** editor. Raw HTML, images and
  unsafe link schemes never render.
- **MODIFIED MNT-READ-05**: the Maintainer browser renders the five
  Reporter-authored source fields through the shared safe renderer. Title, notes,
  comments, checklist, responses, proposal and history stay escaped plain text.
  Changes the Maintainer owner's implemented behavior and needs their approval.
- Developer views that display Reporter-authored source fields use the same
  renderer, so all roles see the same output. No Developer report-detail screen
  exists yet. This change adds a read-only source panel and needs Developer owner
  approval.

### Out of Scope

Report editing and its lifecycle interaction (SEC-09), checklist responses,
discussion, the improve-report AI feature, Reporter frontend screens beyond the
shared editor component, syntax highlighting, real-session integration (owned by
`add-shared-authentication`), and Markdown in any field other than the five
Reporter-authored source fields.

## Capabilities

### New Capabilities

- `reporter-reports`: Reporter create, list-own and read-own report behavior, field
  validation and ownership authorization.
- `safe-markdown`: the shared safe GitHub-flavoured Markdown rendering subset and
  the Write/Preview editing contract used by every role that displays
  Reporter-authored text.

### Modified Capabilities

- `maintainer-report-browser`: MNT-READ-05 changes from "no interpreted Markdown"
  to "Reporter-authored source fields render through `safe-markdown`; everything
  else remains escaped text".

## Impact

- New `src/backend/reporter/` (store, HTTP handler, migration, synthetic-session
  test harness, black-box API tests), following the Developer backend pattern.
- New `src/frontend/shared/markdown/` package consumed by the Maintainer and
  Developer frontends as a local `file:` dependency. Adds `react-markdown` and
  `remark-gfm`. Both apps need dependency updates and must still pass
  `npm run verify`.
- `src/frontend/maintainer/src/App.tsx` source-field rendering. QR-07 fixtures gain
  Markdown and HTML/image injection cases.
- Cross-team approvals: the Maintainer owner for the MNT-READ-05 modification, the
  Developer owner for the source panel and for an additive migration after
  `001_create_reports.sql`.
- Dependencies: `shared-report-storage` (SR-01, 3/4), and a synthetic session until
  `add-shared-authentication` delivers real sessions.
