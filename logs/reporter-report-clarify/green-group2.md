# Green evidence: reporter-report-clarify tasks 2.1, 2.2, 2.3

## Summary

Goal: make the approved group 2 Red tests pass by adding the Reporter check endpoint `POST /api/reporter/report-clarify`. That covers the request and output rules, the service, the Reporter gateway provider, the controller and the wiring.

Changes (all new files in `src/backend/platform/src/main/java/mp4/platform/`):
- `ReportClarifyRules` handles the pure rules.
  - Strict request parsing reuses `mp4.discussion.StrictJson` and `TextRules`, the same parser and text rules as report creation. The body must have exactly `title` and `description`. Duplicate keys and trailing data are refused. The title is 1-200 characters on one line and the description is 1-20,000 characters. NUL and lone surrogates are refused.
  - It holds the trusted instructions (about 2.3 KB) and the untrusted `Report title:` / `Report description (untrusted):` model input.
  - Output validation is strict. The object has exactly `unclear` and `missing`, up to 64 KiB, with no duplicate keys or trailing text. `unclear` has 0-8 items and each quote is 1-300 characters, an exact substring of the draft, and not repeated. `missing` has 0-7 items, each category is one of the 7 names and used at most once. Every question is 1-300 characters on one line and not blank.
  - RC-07b screening rejects a question that contains a URL, an email address, or a credential term as a whole word, ignoring case.
  - `Result.toJson()` writes `{"unclear":[...],"missing":[...]}`.
- `ReportClarifyProvider`: the public provider interface, `clarify(actorId, title, description)` plus `modelName()`.
- `SocLlmReportClarifyProvider` sends `Call(REPORTER, actor, REPORTER_ASSIST, instructions, modelInput, 8192, 60 s)` through the role gateway. A `SummaryFailure` passes through unchanged. Any other exception becomes `SummaryFailure(AI_UNAVAILABLE)` with no cause attached, so its message cannot leak.
- `ReportClarifyService.handle(actorId, body)` returns a `Reply(status, code, retryAfterSeconds, body)`.
  - A missing actor gives 401. Invalid input gives 400 `VALIDATION_FAILED`. Both happen before any provider call.
  - With no provider configured it returns 503 `AI_UNAVAILABLE`.
  - Gateway failures map to: busy 409, rate limited 429 with Retry-After, timeout 504, invalid output 502, everything else 503.
  - It writes one telemetry line per request: `event=reporter_report_check outcome= model= duration_ms= input_tokens= output_tokens= unclear_count= missing_count=`. The line never contains draft text, quotes, questions, model output or exception messages.
- `ReportClarifyController` serves `POST /api/reporter/report-clarify`.
  - The existing `/api/reporter/** hasRole("REPORTER")` rule and CSRF in `AuthConfiguration` already cover this path, so `AuthConfiguration` was not changed.
  - Before the provider is called, the controller mirrors the Developer suggestion checks. The session principal must be a Reporter. The session `CREATED`/`LAST` times must be within the 8 h absolute and 30 min idle limits. The stored account must be active, have the Reporter role and the same auth generation. The persisted session must still exist and match the actor, generation and role.
  - After a 200 it repeats the account and persisted-session checks. If either fails, it discards the result and returns 401 or 403.
  - It refuses a query string, a non-JSON content type or a body over 128 KiB (the report-creation ceiling) with 400. It sets `Retry-After` on 429 and `Cache-Control: no-store` on every response.
- `ReportClarifyConfiguration` registers a `ReportClarifyProvider` only when SoC LLM is enabled, the budgets are set and `SOC_LLM_API_KEY_REPORTER` is valid. It uses the Reporter `SocLlmRoleService` and `modelForRole(REPORTER)`. It always registers a `ReportClarifyService` from `ObjectProvider.getIfAvailable()`, so without a Reporter key the check returns 503 and never calls a provider. The pattern follows `SuggestionProviderConfiguration`.

Checks and results:
- Focused command: exit 0. `ReportClarifyRulesTest` passed 24/24, `ReportClarifyServiceTest` 20/20, and the added `ReportClarifyConfigurationTest` 2/2.
- `compileTestJava`: exit 0. The blocked RC-02 integration test `ReportClarifyAuthenticatedIntegrationTest` now compiles. It was compiled but not run, because it needs the disposable PostgreSQL fixture.
- Regression command: exit 1. 108 tests ran and 107 passed. The one failure is `PostgresSocLlmAdmissionTest > initializationError`, the PostgreSQL fixture precondition (`MP4_AUTH_TEST_JDBC_URL` is not set). It is recorded as NOT RUN, the same as in group 1.
- The SHA-256 hashes of the approved test files are unchanged (listed below).

Review findings and fixes: no reviewer has looked at this yet. The candidate_verifier and security_reviewer steps come next.

Remaining limitations:
- RC-02a/b (wrong role, inactive account, missing or wrong CSRF, and the positive control) is covered only by the integration test, which was not run. The session-freshness and account checks in the controller have no automated test here. Task 2.1 stays open until the PostgreSQL run.
- `input_tokens` and `output_tokens` are always `unavailable`. `SocLlmContracts.RoleGateway.generate` returns only the content, so provider usage cannot reach this layer.
- The service sets no local deadline or in-flight limit. It relies on the gateway's 60 s call deadline and the `REPORTER_ASSIST` admission policy (1 in flight, 3 starts per 5 min). A request thread can therefore be held for up to the gateway deadline.
- Some rules go further than the design text:
  - Credential screening also matches plurals (`passwords`, `tokens`, `secrets`) and `apikey`/`api_key`.
  - A question made only of whitespace is rejected.
  - A quote or question with a lone surrogate is rejected.
  These can reject a rare legitimate result, which shows as a safe 502.
- Lengths are counted in UTF-16 code units, the same as report creation.

## Reflection

- Agent role: implementer. TASK: reporter-report-clarify/2.1, 2.2, 2.3.
- Context supplied: the handoff packet, approval-group2.md, red-group2.md (assumed API), design.md decisions 1-4 and 8, the three approved test files, the Suggestion controller, service, provider and configuration, `SocLlmRoleConfiguration`, `AuthConfiguration`, `SocLlmContracts`, and the report-creation rules in `ReporterReportsHttpHandler`, `StrictJson` and `TextRules`.
- Agent coordination: a single implementer. No other agents were dispatched.
- Test scope: the focused ReportClarify classes plus the socllm and platform SoC LLM regression set. The PostgreSQL-backed tests were not run.
- Test added beyond the baseline: `ReportClarifyConfigurationTest`. Its first run failed because the test context lacked a session-repository mock that `MaintainerSummaryConfiguration` needs. The mock was added in the same way as `SocLlmRoleConfigurationTest`. No production code changed for this.
- Resource measurements: the focused run took about 10 s and the regression run about 49 s (Gradle wall time). Agent token usage: unavailable.

## Technical evidence

- Revision: HEAD `1b2d3b7` (branch `llm-reporter`), with the group 1 and group 3 working tree, the approved group 2 tests, and the uncommitted files below.
- Scratch: `C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/rc-g2-green` (`focused.txt`, `compileTestJava.txt`, `regression.txt`, `regression-counts.txt`).

### Commands

1. `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests 'mp4.platform.ReportClarify*'`. Exit 0, BUILD SUCCESSFUL.
2. `cd src/backend/auth && ./gradlew.bat --no-daemon compileTestJava`. Exit 0, BUILD SUCCESSFUL. This includes `mp4.auth.ReportClarifyAuthenticatedIntegrationTest`, which was compiled but NOT RUN (BLOCKED: no `MP4_AUTH_TEST_JDBC_URL`, `MP4_AUTH_TEST_DB_USER` or `MP4_AUTH_TEST_DB_PASSWORD`).
3. `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests 'mp4.socllm.*' --tests 'mp4.platform.*SocLlm*' --tests 'mp4.platform.SocSuggestion*' --tests 'mp4.platform.MaintainerSocLlm*' --tests 'mp4.platform.ReportClarify*'`. Exit 1. 108 tests completed and 1 failed (the PostgreSQL fixture precondition).

### Per-class counts (regression run)

| Class | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| mp4.platform.ReportClarifyRulesTest | 24 | 0 | 0 | 0 |
| mp4.platform.ReportClarifyServiceTest | 20 | 0 | 0 | 0 |
| mp4.platform.ReportClarifyConfigurationTest (added) | 2 | 0 | 0 | 0 |
| mp4.platform.MaintainerSocLlmConfigurationTest | 3 | 0 | 0 | 0 |
| mp4.platform.SocLlmRoleConfigurationTest | 10 | 0 | 0 | 0 |
| mp4.platform.SocLlmSuggestionProviderTest | 9 | 0 | 0 | 0 |
| mp4.platform.SocSuggestionCredentialReflectionTest | 4 | 0 | 0 | 0 |
| mp4.platform.SocSuggestionPromptContractTest | 1 | 0 | 0 | 0 |
| mp4.platform.SocSuggestionProviderTest | 7 | 0 | 0 | 0 |
| mp4.platform.SocSuggestionProviderTimeoutLifecycleTest | 2 | 0 | 0 | 0 |
| mp4.socllm.MaintainerAdmissionAdapterTest | 2 | 0 | 0 | 0 |
| mp4.socllm.PostgresSocLlmAdmissionTest | 1 | 1 (NOT RUN: no PostgreSQL fixture) | 0 | 0 |
| mp4.socllm.SocLlmHttpProviderTest | 10 | 0 | 0 | 0 |
| mp4.socllm.SocLlmRoleServiceTest | 6 | 0 | 0 | 0 |
| mp4.socllm.SocLlmTaskPolicyTest | 7 | 0 | 0 | 0 |
| mp4.auth.ReportClarifyAuthenticatedIntegrationTest | compiled, NOT RUN (BLOCKED) | | | |

### New main files (SHA-256)

- `src/backend/platform/src/main/java/mp4/platform/ReportClarifyRules.java` `8256c404d0db8ecb47236fd95657239aca051a2ab05f6771f8001d880c4cfc26`
- `src/backend/platform/src/main/java/mp4/platform/ReportClarifyService.java` `1036a49e1380c19bcee0d58406b4ee9b2b01a7f7e42f2050047fd31109da531c`
- `src/backend/platform/src/main/java/mp4/platform/ReportClarifyProvider.java` `f25c90b4cacf10f51b1b5ea2dea3398ff8ecbadcfe910c3b750216d7d17d3e0b`
- `src/backend/platform/src/main/java/mp4/platform/SocLlmReportClarifyProvider.java` `3391dc6c7a9dda287c67a463451b2b09efc614f5b328c1faec5f5e932ed856a1`
- `src/backend/platform/src/main/java/mp4/platform/ReportClarifyController.java` `207a388d82ef37c6ef50a9d5495b0b96d9e67b8139a14034c77ef53a3328ec09`
- `src/backend/platform/src/main/java/mp4/platform/ReportClarifyConfiguration.java` `9cc5797de2093aba9eec4fbac4f4e922ebfaa46a281e538905a4b1f1a2bb5d81`

No existing main file was changed by this group (`AuthConfiguration.java` is untouched).

### Added test (beyond the approved baseline)

- `src/backend/auth/src/test/java/mp4/platform/ReportClarifyConfigurationTest.java` `e63f13082541960ef0819678cff56f50b149e52cd17a8272673ef8c4f35dff3b`

### Approved test hashes (unchanged, match approval-group2.md)

- `src/backend/auth/src/test/java/mp4/platform/ReportClarifyRulesTest.java` `a62f53959e6613bd70e6a8f9e0eadd5516e945ee588503970bad588b45b64270`
- `src/backend/auth/src/test/java/mp4/platform/ReportClarifyServiceTest.java` `40b4487e76078d00df3ceba91b39a035106ba5ef71c5a35331e2a027638dd0ff`
- `src/backend/auth/src/test/java/mp4/auth/ReportClarifyAuthenticatedIntegrationTest.java` `ba130901c974d7473c128fc0f7abcc1b57aaa9b86d733f8467cedfcac55267c5`

## Fix round 1

### Summary

Goal: address the independent review findings. Those were the candidate_verifier coverage FAIL for RC-08b, plus the low and info screening findings. The write paths are the same. The approved tests and the openspec files were not touched.

What changed:
- Only `ReportClarifyRules.java` changed in main code. The other five main files are byte-for-byte the same as in the first green run.
- Item 1 (RC-08b coverage): `ReportClarifyConfigurationTest` has a new test, `reporterKeyWithSocLlmDisabledOrUnsetLeavesTheCheckUnavailable`. It sets a valid `SOC_LLM_API_KEY_REPORTER` with `SOC_LLM_ENABLED=false`, then again with `SOC_LLM_ENABLED` unset. In both cases no `ReportClarifyProvider` and no `socLlmRoleServices` bean is registered, and the service returns 503 `AI_UNAVAILABLE`. No production change was needed.
- Item 2 (credential screen):
  - Questions are first normalized: NFKC, then all Unicode format characters (category Cf, for example U+200B and U+00AD) are removed, then the text is lowercased.
  - `_` and `-` count as word breaks. The screen is checked once with camelCase boundaries split and once without, so both `accessToken` and `OTPs` are caught.
  - New terms: `credential(s)`, `passwd`, `pass word`, `pin(s)`, `otp(s)`, `2fa` / `2 fa`, `one time` / `verification` / `login` followed by `code(s)`, and `cookie(s)`. The earlier terms, plurals and `api key` variants are kept. Matching is still whole-word.
- Item 3 (URL and email screen): the same normalization is applied before matching, without the `_`/`-` word-break step, so email local parts and domains stay intact. Fullwidth `ｈｔｔｐｓ://`, `ｗｗｗ.` and `＠` are caught. `hxxp://` and `hxxps://` are now matched as well. No bare-domain heuristic was added.
- Item 4 (one-line rule): a question is rejected if it contains any character of type CONTROL (all C0/C1 controls, including CR, LF, TAB and U+0085), U+2028, U+2029, U+202A-U+202E or U+2066-U+2069.
- Item 5: a quote that is blank or only whitespace is rejected (`TextRules.isBlank`).
- New test file `ReportClarifyScreeningHardeningTest` (9 tests) covers items 2-5. It includes whole-word negatives that must still pass, such as `tokenizer`, `tokenizer_config`, `secretary`, `passwordless`, `pinpoint`, `spinning`, `cookbook`, `credentialing`, `login page`, `error code`, `iPhone` and a bare `example.com`, and ordinary Unicode (accents, em dash, curly quotes, an emoji).

Checks and results:
- Focused: exit 0. `ReportClarifyRulesTest` passed 24/24, `ReportClarifyServiceTest` 20/20, `ReportClarifyConfigurationTest` 3/3 and `ReportClarifyScreeningHardeningTest` 9/9.
- `compileTestJava`: exit 0. `ReportClarifyAuthenticatedIntegrationTest` still compiles. It was compiled but not run (BLOCKED: no PostgreSQL).
- Regression: exit 1. 118 tests ran and 117 passed. The one failure is still `PostgresSocLlmAdmissionTest > initializationError` (NOT RUN: no PostgreSQL fixture).
- The approved test hashes are unchanged.

Remaining limitations:
- The wider credential list can reject some legitimate questions as a safe 502. Examples: "Does clearing cookies fix it?", "Which pin on the board?", or a question that uses "pass word(s)" in another sense.
- Format-character stripping and NFKC are used only for screening. The accepted question text is returned unchanged.
- Bidi marks U+200E, U+200F and U+061C are not in the one-line reject list, because they were not in the finding. The screens remove them as Cf characters before matching.
- All the earlier limitations still apply: the RC-02 integration test was not run, token usage is unavailable, and the service has no local deadline.

### Reflection

- Agent role: implementer, fix round 1. Context supplied: the coordinator's five review findings and the same packet.
- During editing, a Python helper wrote raw U+2028/U+202A characters into the Java source instead of escapes. This was found by grepping for non-ASCII bytes before any build. It was replaced with numeric code-point comparisons, and the file now contains only ASCII.
- Resource measurements: the focused run took about 13 s and the regression run about 47 s (Gradle wall time). Agent token usage: unavailable.

### Commands (fix round 1)

Scratch: `C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/rc-g2-green` (`fix1-focused.txt`, `fix1-compileTestJava.txt`, `fix1-regression.txt`, `fix1-regression-counts.txt`).

1. `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests 'mp4.platform.ReportClarify*'`. Exit 0, BUILD SUCCESSFUL. 56 tests, 0 failures.
2. `cd src/backend/auth && ./gradlew.bat --no-daemon compileTestJava`. Exit 0, BUILD SUCCESSFUL.
3. `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests 'mp4.socllm.*' --tests 'mp4.platform.*SocLlm*' --tests 'mp4.platform.SocSuggestion*' --tests 'mp4.platform.MaintainerSocLlm*' --tests 'mp4.platform.ReportClarify*'`. Exit 1. 118 tests completed and 1 failed (the PostgreSQL fixture precondition).

Per-class counts changed from the first run: `ReportClarifyConfigurationTest` went from 2 to 3 tests, and `ReportClarifyScreeningHardeningTest` is new with 9. All other classes have the same counts as in the table above.

### SHA-256 after fix round 1

Changed main file:
- `src/backend/platform/src/main/java/mp4/platform/ReportClarifyRules.java` `f2926a38e863b87f78ef6da151612d6049e6012f95449c3ed95b03a667a7edb6` (was `8256c404…`)

Unchanged main files: `ReportClarifyService.java` `1036a49e…`, `ReportClarifyProvider.java` `f25c90b4…`, `SocLlmReportClarifyProvider.java` `3391dc6c…`, `ReportClarifyController.java` `207a388d…`, `ReportClarifyConfiguration.java` `9cc5797d…`.

Added tests:
- `src/backend/auth/src/test/java/mp4/platform/ReportClarifyConfigurationTest.java` `bbc8f2b25af15c69e22fe4e73e7d8f346d39aed69341670a2aa6554b7529a334` (was `e63f1308…`)
- `src/backend/auth/src/test/java/mp4/platform/ReportClarifyScreeningHardeningTest.java` `6ccd86516f31e4f57413e6fa251802b584e07f86aa601b1868d4c1a81e7639d6` (new)

Approved test hashes (unchanged, match approval-group2.md):
- `ReportClarifyRulesTest.java` `a62f53959e6613bd70e6a8f9e0eadd5516e945ee588503970bad588b45b64270`
- `ReportClarifyServiceTest.java` `40b4487e76078d00df3ceba91b39a035106ba5ef71c5a35331e2a027638dd0ff`
- `ReportClarifyAuthenticatedIntegrationTest.java` `ba130901c974d7473c128fc0f7abcc1b57aaa9b86d733f8467cedfcac55267c5`

## Independent review (orchestrator record)

- Round 1: candidate_verifier **FAIL**, one coverage gap: RC-08b had no test with
  AI disabled and a Reporter key set. security_reviewer: 3 Low and 1 Info (credential
  and URL screen gaps, line/bidi characters, blank quotes). All were routed to the
  implementer as fix round 1.
- Round 2 (fresh reviewers): candidate_verifier **PASS**. Focused 56/56, approved
  hashes unchanged, nothing skipped, every earlier finding has a failing-capable
  assertion. security_reviewer: all 5 earlier findings closed, no trust-boundary
  issues, performance fine. Remaining items (open, owner decision):
  1. Low/Med: screen bypass with default-ignorable characters (U+034F,
     U+FE00-FE0F) and cross-script homoglyphs.
  2. Low: terms and URL forms outside the spec list (passphrase, SSH/private key,
     MFA/recovery code, JWT, bearer, javascript:, "at/dot" spellings).
  3. Low (availability): false positives on ordinary questions (cookies, 2FA,
     map pin, token, login code, www.example.com) fail the whole check with 502.
  4. Low: invisible-only questions are accepted (blank question shown).
  5. Info: U+200E/U+200F/U+061C are allowed in questions.
- The orchestrator confirmed that all 12 modified tracked files (groups 1 and 3,
  and the task 1.1 spec edit) still match their recorded checksums, so group 2
  changed nothing outside its new files.
- Tasks 2.2 and 2.3 are marked complete; their stated checks passed. Task 2.1 stays
  open: RC-02a/b (`ReportClarifyAuthenticatedIntegrationTest`) needs PostgreSQL and
  has never run.

## PostgreSQL-backed checks (2026-11-13)

- The owner asked for a Docker PostgreSQL. A disposable container
  `mp4-clarify-test-pg` (`postgres:17-alpine`, already present locally, bound to
  127.0.0.1:55433, database `mp4_auth_setup`) was created. Its random password is
  kept in the session scratchpad only. The tests used `MP4_AUTH_TEST_JDBC_URL`,
  `MP4_AUTH_TEST_DB_USER` and `MP4_AUTH_TEST_DB_PASSWORD`, as `test/run_setup.py`
  does.
- `RunnerSetupTest`: BUILD SUCCESSFUL.
- `ReportClarifyAuthenticatedIntegrationTest`: 5 tests, 0 failures, 0 skipped
  (RC-02a unauthenticated, Developer/Maintainer, inactive Reporter; RC-02b
  missing/wrong CSRF; positive control).
- `PostgresSocLlmAdmissionTest`: 7 tests, 0 failures. This is the first run in
  this work, and it covers the group 1 gateway admission.
- **Limitation:** the RC-02 test was written before the controller existed, but it
  could not run then. Its Red was never observed: it was first run against the
  implemented candidate and passed. The candidate file hashes are unchanged since
  the round 2 review.
- Task 2.1 is marked complete: its stated checks (RC-02a/b, RC-03a/b/c, zero gateway
  calls, no echo) have now all passed.
