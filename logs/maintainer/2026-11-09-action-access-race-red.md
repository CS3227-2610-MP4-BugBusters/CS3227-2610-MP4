# Maintainer action access-race Red baseline — 9 November 2026

The goal is to ensure a late 401/403 from a current-session action clears protected Maintainer UI even after the user selects another report. Added two focused failure cases for session-required and access-denied responses, plus a passing control showing that an old-session denial cannot clear a refreshed session. The targeted run reproduced both new failures because the selected report and protected queue stayed visible. The prior-session control passed. TypeScript and the existing 70 Maintainer tests passed. This baseline is ready for independent review; implementation has not started.

## Technical evidence

- Role: test_designer; task: MNT-READ-04 / MT-13 current-session denial after action selection changes.
- Starting revision: `1b1879148485f0a900129524f33bdedb33b4f1a8`. The targeted and control runs executed at `c459d775dc5ef2ca0a4d530f1bfd7b9703f379c1`, after an unrelated shared-workspace backend/test commit. The Maintainer App source at starting revision `1b1879148485f0a900129524f33bdedb33b4f1a8` has LF-normalized SHA-256 `b4e8121b47414b62dbb5dfe11630c637e89e06533dcf83c095b27b95aaf0140c`; this App source hash is recorded here as the starting reference and is deliberately excluded from the input manifest because it is the implementation under review.
- Added path: `src/frontend/maintainer/src/action-access-race.test.tsx` only.
- Targeted Red command from `src/frontend/maintainer`: `npm test -- --run src/action-access-race.test.tsx --reporter=dot`; exit 1, 1 test file with 2 failures and 1 pass. Both session and denied cases failed because the expected sign-in/denial message never appeared after the old action was aborted by selecting Report 2. Before rejection each case verified Report 2 was selected and the protected queue remained visible.
- Positive control: same test file; a denial from the previous session after refresh leaves the refreshed session's queue and selected Report 2 visible. Passed.
- Existing-suite control from `src/frontend/maintainer`: `npx vitest run --exclude src/action-access-race.test.tsx`; exit 0, 9 files and 70 tests passed.
- Typecheck from `src/frontend/maintainer`: `npm run typecheck`; exit 0.
- Failure mechanism observed in `App.tsx`: `runAction` handles access denial only within `if (current())`; `current()` checks selected report equality and `!signal.aborted`. Selecting Report 2 aborts the in-flight action, so a late current-session 401/403 bypasses `deny`.
- Scope: no application source, prior tests, fixtures, configuration, or OpenSpec files were edited. No unrelated behavior was tested. Backend authorization was not in scope.
- `2026-11-09-action-access-race-inputs.sha256` contains only SHA-256 rows for the new tests, fixture, all nine existing test files (70 controls), runner config/lockfile and relevant OpenSpec files; the mutable App implementation is excluded. Hash algorithm: read file bytes as UTF-8 text, replace CRLF with LF and any remaining CR with LF, encode as UTF-8 without BOM, then SHA-256; rows use lowercase hex, two spaces, and repository-relative path, with no header.

## Fresh independent verification

Confirmed Red with a fresh baseline_verifier, gpt-6-luna/high, on `c459d77`.
The focused cases reproduced two current-session failures and one prior-session
control pass; the other 70 tests and typecheck passed. All 20 input hashes matched.
The mutable App is excluded from the frozen manifest; its starting source hash
was independently compared with `1b18791`. No spec or fixture decision remains.
Proceeding under the feature owner's standing authorization after verified Red.
