# Saved checklist context for Developer AI — 10 November 2026

## Summary

AI checklist generation now reads the last saved draft with the report title and description and asks the model to avoid duplicating existing steps. Unsaved edits and private notes are excluded. Input is limited to 20 steps and 8192 UTF-8 bytes; stale draft or report versions prevent applying suggestions. Save and Share remain separate. Frontend verification passed (56 tests, typecheck and build); backend integration passed (19 tests on disposable PostgreSQL); the prompt contract test passed. Live provider usefulness and timeout reliability have not been tested for the changed prompt.

## Implementation and coordination

Starting revision `6e11b9c855ca7ec0745dc92cb0054cf9fa777c39`, with existing uncommitted work preserved. The coordinator updated the Developer AI OpenSpec to reflect the owner's new context decision. A test designer added backend and frontend cases; an independent baseline verifier confirmed four expected backend failures. The implementer added bounded context and revision checks. A separate security reviewer and candidate verifier examined the candidate. Roles were given scoped paths, requirements and review expectations.

## Findings and fixes

The baseline reviewer found a conflicting old response-key assertion; the designer corrected it and the verifier reran Red. The security reviewer identified unbounded database materialization and a lock-wait race. The implementer added conditional database projection and read the draft after locking the report. A regression observes an actual PostgreSQL lock wait before requiring stale-source rejection. A later backend run found an incorrect SQL parameter index; it was fixed and the integration suite rerun successfully. Existing frontend mocks and the frozen prompt contract were updated for the new response and prompt; a new v4 prompt fixture preserves the prior fixture.

## Verification

- `npm run verify` in `src/frontend/developer`: 56 tests in 13 files, typecheck and production build passed independently.
- `./gradlew test --tests mp4.auth.SuggestionAuthenticatedIntegrationTest --no-daemon` in `src/backend/auth`: 19 tests passed independently against a temporary PostgreSQL database. The user's database was not used.
- `./gradlew test --tests mp4.platform.SocSuggestionPromptContractTest --no-daemon`: passed independently.
- Independent security review: no remaining blocker by inspection; no live provider or browser verification.
- `git diff --check`: passed.

## Reflection and resources

The reviewer used a fresh context to identify a database lock race and oversized-read risk that the initial implementation missed. The controlled test required observable lock waiting rather than timing assumptions. Exact token usage, reasoning tokens, cache use and cost were not available; no token savings are claimed. Tests use a fake provider and do not establish live response quality, production configuration, provider availability or shared quota enforcement.
