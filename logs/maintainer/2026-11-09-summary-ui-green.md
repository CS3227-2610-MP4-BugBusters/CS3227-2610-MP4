# Queue summary UI Green evidence

The Maintainer queue now has an advisory summary panel for an adapter that provides the optional summary query. It sends canonical queue filters without pagination, displays review-required findings with validated report links and included/excluded context, supports retry and explicit regeneration, and ignores stale success responses. Current-session access denials still clear protected data after a filter change. Only the explicit development demo receives a deterministic summary fake; ordinary demo fixtures and production HTTP startup remain unchanged. Type checking, all frontend tests, and the production build passed. The live HTTP summary transport is not wired yet, so production does not expose a summary action and no backend integration is claimed.

Review caught two compatibility issues during Green: the first full suite found the demo fake on ordinary fixture adapters, which exposed a control where old read-only tests required none; the fake is now opt-in only for `npm run demo`. Contract review also aligned parsing with the backend engine's seven truncation fields, 20 included reports, 1,000 total queue members, and Unicode code-point output limits. Added regressions cover these bounds and late access denial. No unresolved UI test failures remain.

## Technical evidence

- Task: MNT-P4.1–4.3 queue summary UI and result validation.
- Approved starting revision: `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b`.
- Candidate tested: working tree based on the same HEAD; no commit created.
- Implementer role: `implementer` (`gpt-6-luna`, high effort, as configured). Actual token count unavailable.
- Allowed application paths changed: `src/frontend/maintainer/src/App.tsx`, `demo.ts`, `domain.ts`, `main.tsx`, `style.css`, new `summary.ts`.
- Added regression coverage: new `queue-summary-ui.test.tsx` was the approved Red baseline and preserved; added `summary.test.ts` and `summary-race.test.tsx`. No prior frontend test files were edited.
- Focused check: `npm test -- --run src/summary.test.ts src/summary-race.test.tsx src/queue-summary-ui.test.tsx` — 3 files, 16 tests passed.
- Full verification: `npm run verify` from `src/frontend/maintainer` — TypeScript passed; 13 test files and 89 tests passed; production Vite build passed.
- Browser smoke: not run by implementer; orchestrator review remains pending.
- Remaining limitation: no live summary HTTP transport or backend endpoint is wired into normal startup. The UI contract is verified only with injected adapters and the explicit deterministic demo fake.

### SHA-256

- `src/frontend/maintainer/src/App.tsx`: `6BF376E946302880215EF6CB753C70C0AC30C97A989F2656BBBE2885676B14E8`
- `src/frontend/maintainer/src/demo.ts`: `C475F357E7A917C5A44B4D49F1B6BA0624F98E2E18E404FC7888B65C80D9CF4B`
- `src/frontend/maintainer/src/domain.ts`: `B9FF6E3E9DEEEA47DF48FFCFC42DF81B81BCAB2B68ADB054D8D49872C092411B`
- `src/frontend/maintainer/src/main.tsx`: `8C8CA327D8E284820A1735EA923032CAB6A5D952330AA6DD06F1196167CB05F8`
- `src/frontend/maintainer/src/style.css`: `EC678346C98417F316301C02D8880E3B7ACBD701E1557195B59FE3BBB056B29C`
- `src/frontend/maintainer/src/summary.ts`: `DC0EB6E02D2EC8A6BA59F1CCEAFBC0A5CC7C182BD8DB5950F48FB9FB5550C4E5`
- `src/frontend/maintainer/src/summary.test.ts`: `96FC546EAB69CC44DAC85B966397C45614B9342516C94383BC7429BDD39181A1`
- `src/frontend/maintainer/src/summary-race.test.tsx`: `C9EB153AD5F45D0D6E22378099363008D59B8839998B0674CBE2F2B9CCF32E6C`
- Approved baseline `src/frontend/maintainer/src/queue-summary-ui.test.tsx`: `C3268ABC5EEDE5A4AE810E41749304317A3C473D7EE8A4E54657A66701626124`

## Candidate repair round 1

Root browser review reproduced an off-page navigation failure at 390px: from queue page 2, generating a summary and clicking source ID `00000000-0000-4000-8000-000000000004` left the details pane unselected because the link handler only accepted IDs on the current list page. The same review found that the long included/excluded ID lists pushed findings thousands of pixels down the narrow view.

The source links now select any validated included/excluded report ID, so the ordinary detail query loads it independent of pagination. Findings appear before the metadata; included/excluded lists are collapsed `<details>` sections with counts. Summary actions are disabled during a mutation. Action completion invalidates summary state for the same authorized session, including a mutation promise that settles after its UI request was aborted due to selection change. A completion from an older session cannot clear new-session summary state.

- New off-page link and late-mutation regressions pass. Approved summary UI tests and all preexisting tests remain unchanged.
- `npm run verify` from `src/frontend/maintainer`: TypeScript passed; 14 test files and 91 tests passed; production build passed.
- Candidate browser recheck: pending root review.

## Independent repair verification

The candidate verifier (gpt-6-luna/low) reran `npm run verify`: typecheck,
91 tests across 14 files, and production build passed. The approved ten-test
baseline remained unchanged. The separate security reviewer
(gpt-6.1-sol/medium) confirmed the mutation-completion and current-session guards;
verdict PASS for this frontend scope.

Checked the explicit development preview in headless Chrome at 390 by 844.
From page two, generated a full-queue summary and opened its off-page source
report; the report detail appeared. Confirmed metadata starts collapsed and
can expand, Closed reports remain browsable, summary generation is disabled
for Closed, and there is no horizontal overflow. An initial smoke script used
an unsuitable exact Playwright label locator for the nested filter label;
corrected the locator and repeated the check successfully. The earlier broken
link was an application defect; the locator timeout was a test-script issue.

These checks use per-page synthetic development data. They do not establish
HTTP summary transport, real authorization, persistence or provider grounding.
- SHA-256 `App.tsx`: `1359E21DFAA4F274A20581B09FC3A0B6E6FE3C0BFF2DCCCC62BB28402F675CE6`
- SHA-256 `summary-race.test.tsx`: `26D6B37F110D449A41C17917BA4AC318DFC99730112F57C37CACE4E2FFA12E0D`
- SHA-256 `summary-link.test.tsx`: `270531C904F485A4CB8849C0623B5809974B1D0E056BF5D51E280213D41CE6FB`
- Approved UI baseline SHA-256 remains `C3268ABC5EEDE5A4AE810E41749304317A3C473D7EE8A4E54657A66701626124`.
