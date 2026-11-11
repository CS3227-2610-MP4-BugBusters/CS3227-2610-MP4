# Checklist Share: local implementation and review

## Human-readable summary

- **Goal and changes:** Implement local publication of a Developer's saved checklist and shared-only reads for the Reporter and Maintainer. Share validates the current assignment, report state, draft revision and report version, rejects empty drafts, and atomically publishes changed steps. During resolution review it supersedes the pending proposal and returns the report to investigation. Unchanged Share is a no-op.
- **Privacy:** The local shared-read adapter returns only shared steps. It checks Reporter ownership or current Developer assignment. Maintainer access requires an explicit trusted callback; the test callback is a synthetic grant, not a production permission policy.
- **Checks and independent review:** Disposable PostgreSQL service and HTTP tests passed for publication, empty and stale rejection, unchanged Share, shared-copy isolation across private saves, wrong-actor denial and malformed input. An independent candidate verifier reran the tests; a separate security reviewer exercised authorization, invalid inputs and transaction rollback. OpenSpec strict validation passed; this verifies planning artifacts only.
- **Local closure race:** A deterministic PostgreSQL test, independently rerun, observed both operations waiting on the report lock. Share-first made a test-only SQL closure stale; closure-first denied Share and preserved the published copy. This tests a simulated closure transaction, not the unavailable real Maintainer closure service.
- **Findings fixed:** Security review found that an inconsistent proposal pointer could supersede another report's proposal or a non-pending proposal. Share now checks proposal ownership and pending status; a new independently verified regression reproduced the original issue and passes after the fix. A second regression found that a maximum safe report version could commit a Share before returning an error. Share now returns `409 VERSION_LIMIT` before mutation; an unchanged Share at the limit still succeeds. Both fixes preserve database state on rejection.
- **Test corrections:** The HTTP test was corrected to compare header names case-insensitively while still requiring `no-store`; its earlier failure was a test issue. The shared-read fixture was corrected to match the real migration's `shared_steps` default after the mismatch made the existing Save endpoint fail. The shared-read test checks no-store for Share and read responses, not the existing Save endpoint, whose contract does not specify it. Independent verification reran the corrected tests.
- **Baseline limitation:** The user authorized implementing from an explicitly unimplemented scaffold after an independent reviewer determined that its exception was not a behavioral Red. The later proposal-ownership and version-limit regressions did produce independently reproduced behavioral Reds before fixes. See [baseline preparation](2026-11-09-checklist-share-baseline-preparation.md).
- **Still open:** Real authentication and session revocation, CSRF, application routing, report-detail projections, production Maintainer permissions, shared schema/lifecycle integration, and real closure-versus-Share tests. Tests use synthetic actors and fixture schema. No full OpenSpec task is marked complete.
- **Resource evidence:** Specialists used scoped contexts for test design, implementation, independent verification and security review. Per-agent token counts, elapsed time and cost were unavailable; no numerical efficiency claim is made.

## Technical evidence

- Starting revision `e8396454237a22c67d60f95fc476bd574a9a1815`, branch `codex/checklist-share`; implementation and tests are working-tree changes.
- Implementer `share_impl`: GPT-6 Luna high. Test designers and candidate/baseline verifiers used GPT-6 Luna low. Security reviewer `share_security_reviewer`: GPT-6.1 Sol medium. Specialists were scoped to the listed artifacts and did not edit one another's owned files.
- Runner commands are documented in `src/backend/developer/README.md`. They require PostgreSQL JDBC and Jackson JAR paths and use temporary PostgreSQL clusters plus synthetic sessions. Local loopback access was sandbox-blocked and the disposable test runs succeeded with escalation.
- Original service test/fixture/runner SHA-256: `0b263499791f4bca397abd041dbc3fc472208a92b4b72047a9854f86928ae09c` / `e807624137830ea4670d3393981c9f0276b2e93af87c32edf780ff969e8b6cf9` / `56aaee7f9f5e47c64b2951eb847ff6fe0c2d3c000777c34ccb8ca4ffdfcf7b61`.
- Proposal guard regression test/runner: `2ba743ae089ae9b26ecce9b66b3f6fff0a9282691d631590001d04cf163c4cf6` / `d551ae098c73c28024f308ffbf8d348db0dca259c1e18dc44bd1d73987ca8d50`.
- Safe-version test originally failed with hash `4511889d2008056ce0bc5a0e166c7d8f53ec0e3240198ccbb66e4c02c900554f`; after the fix it was strengthened to require the exact 409/code and independently rerun at hash `c33e80347055f4225b7ccf7d5a2d9d3becad0c9203fa50ba50d85139fea82e0c`. The original Red applies to the earlier test bytes.
- Shared-read test/runner: `2201a2c114f8b54f0757bde91f2445fcf6b2e5a7e927c5684e89b3e40b5fb865` / `76165926237baa8d4c83d34cf5e9d978f09037bcaf65766ba4d6e6147e77b61a`.
- Synthetic closure-race test/runner: `aad178dc9e514aa3e0c29d5ef2067bbb69d7a9eb8d59fd0679f7b9ed1faa070e` / `c1f6fd97b01ef36ee968eb19356872e860a2ef20e1a581f59eab54f7dd22392c`.
- Candidate service / Share handler / read handler: `8b95530ecd9ca85f6a64b78ae4d41a381e4cf510fe46b40ba528193f5d60ba0b` / `beabe83cc2d544ff3d67edef52bc34c05a3fdb614c9971e031c44fa04e232149` / `3869cab3ef5c9e9dad6e8da7dec0c792ae9741c7f5991c2993543de19fb31df6`.

## Context and interpretation for reflection

The implementation, candidate verification and security review used separate scoped responsibilities. The security reviewer found proposal ownership and version-limit defects that required focused regressions and repair.

The synthetic closure transaction demonstrates locking for the test scenario, not the real Maintainer closure service. The original scaffold exception and later behavioral regressions should be described separately.
