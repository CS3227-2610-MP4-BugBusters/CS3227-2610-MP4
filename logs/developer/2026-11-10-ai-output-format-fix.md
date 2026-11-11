# AI suggestion output format fix — 10 November 2026

## Summary

A live request with synthetic report and checklist data reproduced `AI_INVALID_OUTPUT`: the provider returned three consecutive single-string JSON arrays instead of one array. The parser now combines complete JSON arrays into one advisory list while preserving the existing size, count and text validation. It still rejects malformed output and trailing garbage. A separate review identified an escaped-credential bypass across multiple arrays; the provider now checks decoded strings across all JSON roots.

Independent verification passed for the three authenticated integration cases using disposable PostgreSQL, and for the provider and credential-reflection tests. A live request after the fix through the application remains outstanding; the running backend must be restarted to load the change. Provider reliability and timeouts are not resolved by this change.

## Coordination and findings

Starting revision `6e11b9c855ca7ec0745dc92cb0054cf9fa777c39`, with pre-existing uncommitted project changes preserved. The coordinator ran a synthetic live diagnostic; no real report or credential was logged. A test designer added integration coverage, and an independent verifier reproduced the expected single Red failure. The implementer changed the parser. A security reviewer identified that the old credential guard did not parse multiple roots, so the implementer updated it and the designer added a regression for an escaped credential in the second array. The reviewer rechecked the fix. An independent candidate verifier reran the focused suites.

## Verification and limits

- `./gradlew test --tests mp4.auth.SuggestionTrailingGarbageIntegrationTest --no-daemon --console=plain`: three cases passed using disposable PostgreSQL; user data was not used.
- `SocSuggestionCredentialReflectionTest` and `SocSuggestionProviderTest`: passed independently.
- Security review found no remaining blocker by inspection. The credential regression was written after the fix and therefore has no recorded Red run.
- Live diagnostic before the fix succeeded at HTTP level but did not return valid single-array JSON. No post-fix live application test was performed.
- Actual token, reasoning, cache and cost measurements were unavailable; no savings are claimed.
