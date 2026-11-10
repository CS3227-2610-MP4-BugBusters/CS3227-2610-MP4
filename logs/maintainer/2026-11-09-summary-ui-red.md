# Maintainer queue summary UI Red baseline

Added ten focused Maintainer UI tests for the proposed advisory summary: submission from the full filtered queue, review metadata and server-selected report IDs, truncation/exclusions, safe linked plain-text findings, pending and regenerate states, safe failure with non-AI browsing, invalidation after filters/refresh/mutation/access change, and ignoring late results from a cancellation-ignoring adapter. The focused run failed all ten tests because the current application has no Generate summary control; the first observable failure is the missing accessible button, not a setup or import failure. TypeScript typecheck passed. The test authoring review caught and fixed the fake provider-mode wire value and strict Vitest mock typing. The tests are ready for independent baseline verification; they do not verify backend/transport validation, real authorization, or provider quality.

## Technical evidence

- Task: MNT-P4, proposed UI acceptance for tasks 4.1–4.3; scope is UI behavior only.
- Starting revision: `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b`.
- Changed paths: `src/frontend/maintainer/src/queue-summary-ui.test.tsx` (new) and this evidence log (new). No application code, existing tests, fixtures, or runner configuration changed.
- Contract and fixtures reviewed: `openspec/changes/complete-maintainer-role/design.md`, `verification.md`, `specs/maintainer-queue-summary/spec.md`, `openspec/specs/maintainer-report-browser/spec.md`, existing Maintainer App/domain/demo tests, and `src/backend/maintainer/src/main/java/mp4/maintainer/summary/SummaryContracts.java`.
- Runner/setup: existing Vitest 5.1.0 and React Testing Library from `src/frontend/maintainer`; no unresolved import or runtime setup errors.
- Check: `npm run typecheck` from `src/frontend/maintainer` — PASS (exit 0).
- Red check: `npm exec vitest run src/queue-summary-ui.test.tsx --reporter=dot` from `src/frontend/maintainer` — FAIL (exit 1), 1 test file / 10 tests failed. Each failure reaches `summaryButton()` and reports that no accessible button named “Generate summary” exists. The rendered baseline roles include the queue, filters, report list, details, and refresh controls, but no summary panel/control. This is direct evidence of the missing UI behavior.
- Test SHA-256: `BE5FDDCADF33A21C1A8F7282BAD7F0528554A3824E44AB910474CE9F204C878A`.
- Role/model: test_designer; configured model `gpt-6-luna`, reasoning effort `high`; actual runtime model and token usage are unavailable in the trace.
- Open: independent baseline verifier review and feature-owner approval remain required before implementation. Focused component coverage does not establish endpoint DTO validation, freshness enforcement on the server, or live SoC grounding.

## Corrected baseline and independent review

Strengthened the assertions after review: require the excluded report ID as well
as its reason, and wait for actual Closed report rows before checking that
generation is disabled. Retained Closed queue browsing and the existing void
mutation API. Result fixtures echo the requested queue filters.

The baseline verifier (gpt-6-luna/high) independently reviewed the corrected
oracles and reran typecheck and the focused Vitest command. Typecheck passed;
all ten tests failed at the missing Generate summary control, without setup or
import errors. Verdict: PASS for the UI Red baseline only. Current raw test
SHA-256: `c3268abc5eede5a4ae810e41749304317a3c473d7ee8a4e54657a66701626124`.
Proceeding under the feature owner's standing instruction after verified Red.
