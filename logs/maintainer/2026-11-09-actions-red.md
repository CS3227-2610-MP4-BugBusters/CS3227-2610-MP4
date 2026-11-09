# Maintainer action UI Red baseline — 9 November 2026

The goal is to add explicit priority, assignment, resolution review and plain-text discussion controls to the Maintainer report detail. Fourteen focused tests cover operation payloads, explicit review, required reasons, pending duplicate suppression, conflict recovery, successful refresh behavior and late responses after selection/access changes. TypeScript typecheck and the existing 40 read-only/auth tests pass. The targeted suite fails 14/14 because the current read-only App exposes none of the required action controls or workflows. This demonstrates missing UI behavior and does not establish backend behavior.

## Technical evidence

- Role: test_designer; task IDs 2.1, 2.2, 3.1, 3.2.
- Starting revision: `e63dc47ef0b611e04239c2ca0e0d444325674b7b`.
- Red command: `npm test -- --run src/maintainer-actions.test.tsx` from `src/frontend/maintainer`; exit 1, 14 failed on absent action UI.
- Setup checks: `npm run typecheck` passed; `npm test -- --run src/App.test.tsx src/query.test.ts src/signin.test.tsx src/auth-client.test.ts src/auth-action-race.test.tsx` passed 40/40.
- Added paths: `src/frontend/maintainer/src/maintainer-actions.test.tsx`, `src/frontend/maintainer/src/maintainer-actions.test-fixtures.ts`.
- Fixture: controlled active Maintainer, deterministic report source clones, and recording mutation methods with expectedVersion, priority, developerId/reason, proposalId/proposalVersion/reason, reviewed true, reopen reason and plain comment text.
- Coverage: uniquely labelled Report priority; list/detail refresh; initial assignment; different-target reassignment with reason and expected version; explicit review gate/current proposal identity; proposal return; empty required reason; reopen reason; markup-like comment; duplicate pending submission; 409 STALE_VERSION input preservation/no retry and specific conflict alert assertion; late mutation response after selection change and access denial.
- Hash manifest: SHA-256 computed over UTF-8 bytes after CRLF-to-LF normalization. Covers new tests/fixture, package/Vite/TypeScript runner config, existing tests and relevant specs.
- Application sources and prior tests were not edited. Awaiting independent baseline verification.

## Independent baseline verification

Confirmed Red with a fresh baseline_verifier (gpt-6-luna/low) on setup checkpoint 8645dc1, with frontend source unchanged from e63dc47. Targeted action tests failed 14/14 for missing behavior; existing 40 controls and typecheck passed. All 13 LF-normalized input hashes matched. Corrected the earlier one-report race fixture, raw-CRLF hash mismatch, ambiguous priority selector and conflict-feedback assertion before freezing this baseline. This is UI-only evidence; have not verified backend permissions or transactions. Proceeded under the feature owner's standing instruction to implement verified, unblocked work.

