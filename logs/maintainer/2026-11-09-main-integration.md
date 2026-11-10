# Main integration checkpoint

Reviewed and merged the authenticated Developer checklist journey in PR #9.
Confirmed that PR #8's checklist-sharing patch was included, then closed #8 as
superseded without deleting its branch. Pulled main into the Maintainer branch
and preserved both Developer packaging and the Maintainer backend source set.
The resolved tree matched the independently tested compatibility tree exactly.

Verified compatibility with the existing Maintainer work: all 73 frontend tests
passed, and the combined backend run passed 63 tests with only the 21 expected
queue-summary stub failures. These results do not establish real Maintainer
storage, HTTP mutations, production deployment or live SoC integration.

## Integration decisions carried into the remaining work

- Reuse the authenticated Developer draft, Share and shared-read routes. Supply
  the missing real Maintainer permission callback rather than duplicate sharing.
- Preserve draft-only saves and unchanged Share behavior. Check real closure
  against changed Share in both transaction orders using the shared report row.
- Preserve the five-column proposal schema and JavaScript-safe version limit.
- Package both role frontends; retain both frontend distribution properties.
- Keep production summary generation closed until a real gateway and durable
  quota adapter are configured. Development fixtures remain explicitly labelled.

## Technical evidence

- Reviewed PR #9 head: `b6a61236c8e324b9a2d1c6645a62b4eeb0f12aba`.
- Main merge: `88f1eb414bf51c8f939df068410e1a3c30ee7bde`.
- Maintainer merge: `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b`.
- Tested combined tree: `74624eae28bdbe64a112cd8675ac0f5ed3827089`.
- Separate candidate and compatibility reviewers used gpt-6.1-sol/low;
  the security reviewer used gpt-6.1-sol/medium. Token measurements unavailable.
- Bare PR #9: backend 46 passed; Developer frontend 15 passed; Maintainer
  frontend 40 passed; packaged real-session checklist and static-asset checks
  passed. All ten hosted checks succeeded at the reviewed head.
- Combined tree: backend 84 tests, 63 passed, 21 expected summary failures;
  Maintainer frontend 73 passed and Developer frontend 15 passed, with typecheck
  and production builds passing.

## Summary Red recheck after integration

Rechecked the still-unimplemented summary with a fresh baseline verifier
(gpt-6-luna/high) at `5355a23`. The focused command, from `src/backend/auth`
with the installed Adoptium 21 JAVA_HOME, was:

```powershell
.\gradlew.bat --no-daemon test --tests mp4.maintainer.summary.QueueSummaryTest
```

Compiled successfully: 21 tests failed for missing behavior, with no errors or
skips. The approved test's LF-normalized SHA-256 remained
`fe5cc024e52378dd3108e222d18fd9a93a175dbc485e9122ad3b76bc1aa0849c`.
Only `src/backend/auth/build.gradle` differed from the historical 30-input
manifest, due to the reviewed Developer source includes. Retained that manifest
as historical evidence rather than silently rewriting its baseline. Proceeded
under the feature owner's standing instruction after verified Red.
