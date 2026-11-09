# Tasks

Every behavior task follows [tdd.md](../../../workflow/tdd.md):
`test_designer` Red, a fresh `baseline_verifier`, feature-owner approval,
`implementer`, then a fresh `candidate_verifier`. Tasks marked **[sec]** also need
a separate `security_reviewer`. Build handoff packets with
[agent-context.md](../../../workflow/agent-context.md).

Groups 2 (backend) and 3 (shared Markdown) depend on nothing but group 1, so they
can run in parallel in separate worktrees. Groups 4 and 5 depend on group 3.

## 1. Approvals and scaffolding

- [ ] 1.1 Get Maintainer owner approval for the MNT-READ-05 delta and Developer owner approval for the D7 migration and the D9 source panel. Verify by recording each approver and date in `logs/reporter/`. Groups 4 and 5 and task 2.2 do not start without the matching approval.
- [x] 1.2 Scaffold `src/frontend/shared/markdown/` (package.json with React peer dependency, pinned `react-markdown` and `remark-gfm`, vitest + jsdom, tsconfig, `npm run verify`). Verify that `npm install` and `npm run verify` run with one placeholder test.
- [ ] 1.3 Scaffold `src/backend/reporter/` (README, `migrations/`, `src/`, `test/`) and a disposable-PostgreSQL runner modelled on `src/backend/shared/test/run-shared-report-storage.sh`. Verify that the runner creates and drops a fresh database applying `001_create_reports.sql`.

## 2. Reporter backend (`reporter-reports`)

- [ ] 2.1 Write `workflow/reporter/reporter-reports-api-contract.md` from RPT-01..07 and design D6 (paths, body/response JSON, validation codes, 404 equivalence, error envelope, `no-store`). Verify that the feature owner approves it before 2.2 Red starts. It is read-only for every agent.
- [ ] 2.2 Red/Green the D7 migration: detail fields default to `''`, timestamps are set, `001` → `002` → checklist migration applies cleanly on a fresh database. Verify with a migration test via the 1.3 runner, plus the existing shared-report-storage and checklist tests still passing.
- [ ] 2.3 **[sec]** Red/Green RPT-01, RPT-02 and RPT-03 (create, validation, source fidelity) through the HTTP handler and synthetic-session harness. Verify with `create_report_api_test.py` and `create_report_validation_test.py` covering every RPT-01..03 scenario, including the 20,000 and 20,001 boundaries and the U+0000 and unpaired-surrogate cases.
- [ ] 2.4 **[sec]** Red/Green RPT-04 and RPT-05 (list own, read own, R1/R2 isolation, 404 equivalence, pagination validation). Verify with `list_own_reports_api_test.py` and `read_own_report_api_test.py`, including a byte-for-byte comparison of the 404 bodies for "other Reporter's report" and "nonexistent ID" (excluding `requestId`).
- [ ] 2.5 **[sec]** Red/Green RPT-06 and RPT-07 across all three endpoints (401, 403 for inactive, Developer and Maintainer actors, ignored identity in body/query/header, safe error body, `Cache-Control: no-store`). Verify with `reporter_access_api_test.py`.
- [ ] 2.6 Document build and run steps in `src/backend/reporter/README.md` and verify that a fresh clone following them reproduces 2.2–2.5 passing.

## 3. Shared safe Markdown (`safe-markdown`)

- [ ] 3.1 **[sec]** Red/Green `MarkdownView` for MDR-01 (subset, verbatim code, disabled task checkboxes, no heading IDs). Verify with vitest DOM assertions in the shared package.
- [ ] 3.2 **[sec]** Red/Green MDR-02, MDR-03 and MDR-04 (raw HTML as literal text, no img element or fetch for images, scheme allow-list with `rel`, case and whitespace variants of `javascript:` and others). Verify with vitest tests that assert no `script`, `img` or `iframe` elements, no `on*` attributes, a stubbed `fetch` and `Image` that are never called, and link attributes.
- [ ] 3.3 Red/Green MDR-05 (error boundary fallback, nesting cap). Verify with a 20,000-character pathological fixture that completes in under 1 s in vitest and a forced render error that shows escaped source.
- [ ] 3.4 Red/Green `MarkdownEditor` for MDR-06 (Write/Preview tabs, preview equals `MarkdownView` output, source unchanged by switching, "Nothing to preview", ARIA tab roles and keyboard). Verify with vitest and `@testing-library/user-event` tests.
- [ ] 3.5 Document usage and the safety rules in `src/frontend/shared/markdown/README.md` and verify that `npm run verify` passes in the package.

## 4. Maintainer adoption (MNT-READ-05 delta)

- [ ] 4.1 Add the `file:` dependency to `src/frontend/maintainer` and verify that `npm install` and `npm run verify` still pass with no behavior change.
- [ ] 4.2 **[sec]** Red/Green the modified MNT-READ-05: the five source fields go through `MarkdownView`, and the title, notes, comments, checklist, responses, proposal and history stay escaped. Verify with updated QR-07 tests in `App.test.tsx` (Markdown renders, `<img onerror>` and image URL inert, title `**bold**` literal) and the narrow-viewport overflow scenario. All existing QR-01..08 tests must still pass under `npm run verify`.
- [ ] 4.3 Add Markdown and injection examples to the demo fixtures in `demo.ts` and verify them visually in `npm run demo` at mobile and desktop widths. Record screenshots or notes in `logs/reporter/`.

## 5. Developer adoption (MDR-07)

- [ ] 5.1 Add the `file:` dependency to `src/frontend/developer` and verify that `npm install` and `npm run verify` still pass.
- [ ] 5.2 **[sec]** Red/Green a read-only `ReportSourcePanel` (D9) rendering the five fields through `MarkdownView`, with the title as plain text. Verify with a vitest test asserting the same DOM as the Maintainer for the MDR-07 fixture, and `npm run verify` in `src/frontend/developer`.

## 6. Cross-role integration

- [ ] 6.1 Verify MDR-07 end to end. Create a report through the Reporter API harness with the MDR-07 fixture, feed the returned detail to the Maintainer browser (HTTP adapter against a local stub serving that JSON) and to the Developer panel, and confirm identical rendering and literal `<b>x</b>`. Record the commands, revision and outcome in `logs/reporter/`.
- [ ] 6.2 Run `openspec validate reporter-markdown-reports --strict --no-interactive` and every touched package's verify command on one revision. Record results, agent roles, models and token usage, or mark them unavailable, in `logs/reporter/`.

## Workflow follow-up

- Real-session verification of RPT-06 once `add-shared-authentication` provides sessions. Not part of this change's completion.
- On sync or archive, reword the Maintainer spec prose "Use no raw HTML or interpreted Markdown" under "UI state and concurrency" to match the modified MNT-READ-05 (design Open Question).
- Archive after the owners' review requirements are met.
