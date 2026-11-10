# Report discussion backend

One comment thread per report, shared by every role (`openspec/changes/report-discussion`, design D1-D3).
- **Requirements:** `openspec/changes/report-discussion/specs/report-discussion/spec.md` (DSC-01..06).
- **Approved HTTP contract:** `workflow/reporter/report-discussion-api-contract.md` (revision 1).

## Layout

Package `mp4.discussion` in `src/mp4/discussion/`, plain JDK and JDBC only:
- `ReportDiscussionService`: takes a trusted actor `{id, role, active}` from the authentication layer, decides
  participation and reads or appends `report_comments` rows. Posting never touches `reports` (D3).
  The storage column is `text` (shared with the Maintainer's table); the API field is `body`.
  Display names are joined from `public.app_accounts` at read time (D1); a missing account gives `null`.
- `ReportDiscussionJson`: the comment and page JSON from the contract.
- `StrictJson` (moved from the Reporter's `ReporterJson`) and `TextRules` (moved from the Reporter's
  `validateText` helpers): the strict JSON reader and text rules shared with the Reporter endpoints (D2).

## Status

- Reporter endpoint `GET`/`POST /api/reporter/reports/{id}/comments` (task 2.2), routed from
  `src/backend/reporter/src/ReporterReportsHttpHandler.java`.
- Not yet: DSC-03 body length, U+0000 and surrogate rules and the DSC-05 rate limit (task 2.3); Developer and
  Maintainer participation and endpoints (task 2.4). Until 2.4 the service treats every non-Reporter actor as a
  non-participant.

## Tests and deployment

- Tests live with the Reporter harness: see "Run the tests" in `src/backend/reporter/README.md`.
- `src/backend/auth/build.gradle` compiles `src/` and the deployment Dockerfile copies it (design D7).
