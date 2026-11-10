# AI output recovery and cross-role check — 10 November 2026

## Summary

The live adversarial fixture previously returned an array of quoted suggestions with missing commas. The backend now conservatively handles whitespace-separated JSON strings within arrays, then applies the same strict parsing, step limits and credential checks. Other malformed output remains rejected. This does not address provider timeouts or establish prompt-injection resistance.

Independent verification passed five suggestion-format tests, four credential-reflection tests and one authenticated cross-role HTTP journey on disposable PostgreSQL. The cross-role check uses synthetic accounts and data; browser, deployed-environment and real Reporter UI integration remain open.

## Coordination and findings

Starting revision `0478d9f`. The test designer reproduced one intended failure, and a fresh baseline verifier confirmed the result. The implementer added normalization before both output parsing and decoded-credential inspection. An independent security reviewer identified that adjacent quoted strings without whitespace were also accepted; the implementation was tightened and a negative test added. The security reviewer rechecked the candidate. An independent verifier ran the final test suite. The escaped-credential test was added after the implementation started; no separate Red result is claimed for it.

## Verification and limits

- `python3 src/backend/auth/test/run_setup.py --tests mp4.auth.SuggestionTrailingGarbageIntegrationTest --tests mp4.platform.SocSuggestionCredentialReflectionTest --tests mp4.auth.CrossRoleJourneyIntegrationTest`: independently passed, 5, 4 and 1 tests respectively, against a disposable local database.
- The regression includes malformed text, missing whitespace, non-string output and an escaped synthetic credential. Security review was static in addition to automated tests.
- Live provider output was observed before this change; a post-fix live application run and human usefulness scoring were not completed.
- Token usage, cached tokens, reasoning tokens and cost for development agents were unavailable; no savings are claimed.
