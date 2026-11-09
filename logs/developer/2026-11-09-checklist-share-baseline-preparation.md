# Checklist Share: baseline preparation

## Human-readable summary

- **Goal:** Start a repeatable test for publishing a saved checklist and protecting the report's resolution lifecycle.
- **Finding:** The current backend provides private checklist Save but has no Share service or HTTP route. The existing synthetic harness would return a fallback 404; that does not test Share behavior and is not a valid behavioral Red.
- **Independent review:** A separate verifier confirmed the missing Share seam. A proposal table exists in a Save test fixture, but no shared proposal or closure service is available for a real Share-versus-closure test.
- **Next setup:** Provide a minimal local Share seam and a disposable proposal fixture for a focused service test. Test actual closure races only when a closure service using the shared transaction boundary exists. The contract requires empty drafts to be rejected, changed publication to update the report version, and a changed publication during resolution review to supersede the proposal atomically.
- **Limitations:** No application code or checklist tests were changed and no OpenSpec task was marked complete. Real authentication, report-detail access and lifecycle integration remain open. Token usage, cost and elapsed time were unavailable.

## Technical evidence

- Starting revision: `e8396454237a22c67d60f95fc476bd574a9a1815`, branch `codex/checklist-share`.
- `share_test_designer` (GPT-6 Luna low) inspected the OpenSpec change, `workflow/developer/checklist-storage-contract.md`, existing store, HTTP handler and fixtures. It found no callable Share behavior and made no edits.
- `share_baseline_verifier` (GPT-6 Luna low) independently inspected the same boundary and confirmed the setup blocker. It distinguished the existing Save fixture's synthetic proposal table from an implemented closure service.
- No Share behavioral test was run; a missing route or method is setup evidence rather than a test of the contract. See `workflow/tdd.md` for the required Red and review gates.

## Follow-up: scaffold baseline

A minimal Share interface and explicitly unimplemented service were added as
local setup. A test designer added a disposable PostgreSQL fixture, contract
test and runner. Setup completed, but the first Share call threw the scaffold's
`UnsupportedOperationException` before any behavior assertion ran. An
independent baseline verifier reproduced this and classified it as an
unimplemented-scaffold result, not a verified behavioral Red. The full Share
suite has therefore not passed and no implementation gate is claimed.

- Scaffold: `src/backend/developer/src/ChecklistShareService.java` and
  `UnimplementedChecklistShareService.java`.
- Tests: `ChecklistShareServiceIntegrationTest.java`,
  `checklist-share-fixture.sql` and `run-checklist-share-service.sh` under
  `src/backend/developer/test/`.
- Test designer `share_red_designer` and independent verifier
  `share_red_verifier` used GPT-6 Luna low. Both used a disposable PostgreSQL
  instance; sandbox loopback restrictions required an escalated run.
- Fixture SHA-256 `e807624137830ea4670d3393981c9f0276b2e93af87c32edf780ff969e8b6cf9`;
  test `0b263499791f4bca397abd041dbc3fc472208a92b4b72047a9854f86928ae09c`;
  runner `56aaee7f9f5e47c64b2951eb847ff6fe0c2d3c000777c34ccb8ca4ffdfcf7b61`.
