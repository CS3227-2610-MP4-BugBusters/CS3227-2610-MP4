# Maintainer read-only increment - 7 November 2026

Completed the isolated MNT-P1 frontend against MNT-READ-SPEC-1. This implements the queue, intersecting status/priority/assignee filters, pagination and read-only report evidence through an injected query interface. Shared Java endpoints, real sessions, server authorization, persistence, mutations, AI and deployment remain outside this result.

## Coordination and scope

Started from the Maintainer SDD baseline, inspected the current remote and found only its README on main. Kept unrelated local planning files untouched. Created a local feature branch based on the existing SDD work; no remote update or new PR was part of this implementation run.

Assigned a specification agent to define the increment and QR-01 through QR-08 before implementation. An independent reviewer found no critical scope/security contradiction. Assigned one implementation writer to `src/frontend/maintainer/`, including tests and local tooling. The coordinator maintained specification/status and evidence files separately. No concurrent source writers or shared-file conflicts occurred.

Used inherited agent model/settings, a five-minute specification budget, twenty-minute implementation budget, ten-minute independent review budget and five-minute repair budget. Allowed one implementation writer, a separate read-only verifier and at most two repair rounds. Used one repair round. No live LLM/provider calls or application AI experiments occurred. Token, reasoning-token and cache measurements were unavailable and are not estimated.

During implementation, coordinator inspection requested bounded HTTP streaming, controller cleanup, current-session denial handling, replacement-adapter identity gating and explicit test oracles instead of repeating the filter implementation. These were incorporated before the candidate handoff. The verifier did not edit source or tests.

## Independent findings and repair

The first independent verdict failed QR-02: comparing ISO timestamp strings lexically mishandled fractional seconds and equivalent instants with different textual forms. The remaining seven criteria passed within frontend scope. Returned the finding to the implementation writer, changed the fake comparator to numeric instants followed by ID, and added fractional/equivalent timestamp regressions. Independent re-verification passed QR-01 through QR-08.

| Criteria | Observed result and evidence |
| --- | --- |
| QR-01 | PASS: read-only list/details cover source, investigation, checklist, responses, discussion, proposal and history; component and browser checks expose no mutation/AI controls. |
| QR-02 | PASS after repair: explicit fixture IDs verify filters and pagination; unsorted/fractional/equivalent timestamps verify chronological order and ID ties. |
| QR-03 | PASS: HTTP adapter tests check GET paths, query validation, same-origin credentials, no-store, cancellation, nested payload rejection and byte-bounded responses. |
| QR-04 | PASS for UI handling: initial wrong-role/inactive/session failures prevent protected requests; access failures clear data; refresh and adapter replacement recheck identity. Real server authorization is not established. |
| QR-05 | PASS: component checks distinguish loading, empty, network/retry and missing-detail states and use fixed safe errors. |
| QR-06 | PASS: deferred cancellation-ignoring responses cover filter, selection, refresh and denial races, including current-session obsolete denials and old-session denial isolation. |
| QR-07 | PASS: malicious-looking source text renders without injected elements; accessible controls and desktop/mobile browser behavior were checked. |
| QR-08 | PASS: production entry uses HTTP with no fallback. Build output contains no demo chunk or inspected fixture strings; production HTTP smoke confirmed identity-first behavior. |

## Executed checks

From `src/frontend/maintainer/`:

- `npm.cmd ci`: independent clean install passed. The first attempt encountered Windows EPERM because the active preview held a native dependency; stopping the preview and retrying resolved it without source changes.
- `npm.cmd run verify`: independent final execution passed typecheck, 30 tests in two files and the production build. Tests are in `src/App.test.tsx` and `src/query.test.ts`.
- `npm.cmd audit --json`: exit 0, no reported vulnerabilities at the checked lockfile. This is a dependency advisory check, not proof of application security.
- Production output inspection found only HTML, CSS and application JavaScript, with no synthetic fixture chunk or inspected fixture strings.

Chrome/Playwright demo smoke passed at desktop 1440x1000 and mobile 390x844: 63 total reports, 50 then 13 rows, bounded pagination, filter intersection yielding two rows, an empty intersection, cleared detail after filtering, detail sections, escaped markup and no horizontal overflow or page errors. An initial browser script used an overly exact label-text locator and timed out; the accessible combobox names were verified and the script passed using role/name locators. No source change was needed for that tooling issue.

A separate production-preview smoke intercepted synthetic HTTP responses: a 401 identity response produced the sign-in message with no protected reads, raw error disclosure or fake fallback; valid identity/list/detail GET responses rendered the expected report. Transport interception is not a real backend integration test.

## Candidate and limitations

The companion `2026-11-07-read-only-inputs.sha256` records normalized UTF-8/LF hashes for this source/specification snapshot. The older SDD manifest is historical evidence for its previous document baseline, not a claim that the current files remain unchanged. Also repaired corrupted range/arrow typography in the baseline specifications without changing requirements.

Updated package instructions and specification/task/matrix status after verification. Checked this AI-generated summary against the actual tool results and agent handoffs; teammate review and human acceptance are not claimed. Browser screenshots remain local QA artifacts. Real authentication, PostgreSQL behavior, provider integration, production deployment and controlled reflection experiments still require their own evidence.

Subsequently prepared this verified increment for PR #1 following the request to update the PR. Limited publication to the feature package, its specification/status updates and sanitized evidence.
