# Proposal

## Why

Reporters need to file, list and read their own reports. A description often
holds steps, logs and environment details, which are easier to read with
GitHub-style formatting such as lists, code blocks and tables. The shared `reports` table (SR-01) stores only title
and description, and no Reporter API exists. The Maintainer browser currently
shows every source field as escaped literal text (MNT-READ-05), so any formatting
a Reporter writes would appear as raw `##` and `*` characters to the people
triaging and investigating the bug.

## What Changes

- **RPT-01..07** Reporter backend: create a report, list own reports, and read one
  own report, with server-side ownership checks, validation, the shared error
  envelope and no client-supplied identity.
- **RPT-09..10** (owner decision 10 Nov): a Reporter can edit the title and
  description of, or permanently delete, their own report while it is unassigned
  and `SUBMITTED`, with an optimistic `version` check. Once assigned it is locked.
- A report has **only two Reporter-authored fields: a plain-text `title` and a
  Markdown `description`**. Reproduction steps, expected and actual behavior, and
  environment are not report fields. They are things the future improve-report
  AI suggests the Reporter add to the description.
- **RPT-08** Rate limit: at most **3 report creations per Reporter per rolling
  minute**. Further creates get 429 with `Retry-After`.
- An additive Reporter-owned migration adds `created_at` and `updated_at`, needed
  for newest-first listing and the rate-limit window. SR-01 has no timestamps.
- A Reporter-owned migration adds an append-only creation log, so deleting a
  report cannot reset the rate-limit window.
- Store `description` as **Markdown source exactly as typed**. The server never
  renders, converts or stores HTML.
- **MDR-01..05** A shared safe Markdown capability: a GitHub-flavoured subset
  renderer and a GitHub-style **Write | Preview** editor. Raw HTML, images and
  links never render. Links show as text with their destination (owner
  decision 9 Nov: no links at all).
- **MODIFIED MNT-READ-05**: the Maintainer browser renders the report
  `description` through the shared safe renderer. Title, the other source fields,
  notes, comments, checklist, responses, proposal and history stay escaped plain
  text. Changes the Maintainer owner's implemented behavior and needs their
  approval.
- Developer views that display the description use the same renderer, so all
  roles see the same output. No Developer report-detail screen exists yet. This
  change adds a read-only description panel and needs Developer owner approval.

### Out of Scope

Editing or deleting a report after it is assigned, and that lifecycle
interaction (SEC-09), soft delete or undo, checklist responses,
discussion, the improve-report AI feature (including its suggestions for
reproduction steps, expected and actual behavior, and environment), Reporter
frontend screens beyond the shared editor component, syntax highlighting,
real-session integration (owned by `add-shared-authentication`), Markdown in any
field other than `description`, and rate limits on anything other than report
creation.

## Capabilities

### New Capabilities

- `reporter-reports`: Reporter create, list-own and read-own report behavior,
  two-field validation, ownership authorization and the creation rate limit.
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
- `src/frontend/maintainer/src/App.tsx` description rendering. QR-07 fixtures gain
  Markdown and HTML/image injection cases. The Maintainer DTO's other source
  fields stay as they are; this backend doesn't supply them.
- Cross-team approvals: the Maintainer owner for the MNT-READ-05 modification, the
  Developer owner for the description panel and for an additive timestamp
  migration after `001_create_reports.sql`.
- Dependencies: `shared-report-storage` (SR-01, 3/4), and a synthetic session until
  `add-shared-authentication` delivers real sessions.
