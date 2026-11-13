# Green evidence: reporter-report-clarify task 2.4 (group 2, round 2)

## Summary

Goal: make the approved round 2 Red tests pass. Owner decision 2026-11-13 (option b): a question that fails the
RC-07b screen (URL, email address or credential request) drops only its own item instead of failing the whole check.
If every item is dropped, the check still fails safely. RC-07c: hidden, format and direction-mark characters, or a
question with no visible character, reject the whole result.

Changes (one file, `src/backend/platform/src/main/java/mp4/platform/ReportClarifyRules.java`):
- Question validation is split in two. `question(...)` applies the format rules: length, not blank, storable, one line,
  and now no hidden character. Any failure still rejects the whole result. `screened(...)` applies the RC-07b URL,
  email and credential screens and returns a boolean instead of throwing.
- `validateOutput` still runs every structural check on every item, dropped or not: exact keys, quote is an exact,
  unique draft substring, and category is valid and unique. A screened item is then skipped, so the kept items stay in
  their original order. If at least one item was dropped and none remain, it throws the usual
  `Invalid report check output`, which the service already maps to 502 `AI_INVALID_OUTPUT`. A genuinely empty result
  stays valid.
- New RC-07c check `hasHiddenCharacter`: rejects any code point of general category Cf (format, including
  U+200E/U+200F/U+061C, U+00AD, U+200B-U+200D, U+2060, U+FEFF) or in the Unicode Default_Ignorable_Code_Point set
  (U+034F, U+115F-U+1160, U+17B4-U+17B5, U+180B-U+180F, U+200B-U+200F, U+202A-U+202E, U+2060-U+206F, U+3164,
  U+FE00-U+FE0F, U+FEFF, U+FFA0, U+FFF0-U+FFF8, U+1BCA0-U+1BCA3, U+1D173-U+1D17A, U+E0000-U+E0FFF). With these
  rejected, the existing not-blank rule means every accepted question has a visible character.
- Screening normalization now strips default-ignorable as well as format characters after NFKC, as design Decision 2
  states. Because hidden characters now reject the result first, this is defense in depth.
- `ReportClarifyService` is unchanged. No `dropped_count` telemetry field was added. Dropped question text is never
  logged or kept: the drop path only increments a local counter.

Checks and results: the focused command passed 70/70. The PostgreSQL regression command passed 143/143 across 18
classes, with 0 skipped. All four approved test files keep their approved SHA-256 hashes.

Remaining limits:
- Cross-script lookalikes and terms outside the list are not screened. The owner accepted this limit.
- "Visible" means not whitespace and not hidden. A question made only of combining marks or U+2800 BRAILLE PATTERN
  BLANK is not treated as invisible.
- The default-ignorable list is hard-coded from Unicode DerivedCoreProperties. Java has no built-in property for it.
- No telemetry counts dropped items.

## Technical evidence

- Role: implementer. Task: reporter-report-clarify/2.4. Model: claude-opus-5-5. Effort and token use are not exposed.
- Revision tested: HEAD `1b2d3b7f5a787fe2c73d4bbc79a54f6f9e016a5c` (branch `llm-reporter`) plus the uncommitted groups 1-3 working tree and this change. Not committed.
- Approved baseline: [approval-group2-round2.md](approval-group2-round2.md). Red: [red-group2-round2.md](red-group2-round2.md), 70 tests with 10 failures.
- Context supplied: approval and Red logs, spec RC-07b/RC-07c, design Decision 2, `ReportClarifyRules.java`, `ReportClarifyService.java`, the approved test files, `mp4.discussion.TextRules`.

### Commands

1. Focused, from `src/backend/auth`:
   `./gradlew.bat --no-daemon test --tests 'mp4.platform.ReportClarify*'`
   Exit 0, BUILD SUCCESSFUL. Output: `C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/rc-g2b-green/focused.txt`

   | Class | Tests | Failures | Errors | Skipped |
   |---|---|---|---|---|
   | ReportClarifyConfigurationTest | 3 | 0 | 0 | 0 |
   | ReportClarifyDropScreenedTest | 14 | 0 | 0 | 0 |
   | ReportClarifyRulesTest | 24 | 0 | 0 | 0 |
   | ReportClarifyScreeningHardeningTest | 9 | 0 | 0 | 0 |
   | ReportClarifyServiceTest | 20 | 0 | 0 | 0 |
   | Total | 70 | 0 | 0 | 0 |

2. Regression with the disposable PostgreSQL fixture, from `src/backend/auth`, with
   `MP4_AUTH_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55433/mp4_auth_setup`, `MP4_AUTH_TEST_DB_USER=postgres`, and
   `MP4_AUTH_TEST_DB_PASSWORD` read from the scratchpad password file. The password was not printed, and a check
   found it in none of the output files.
   `./gradlew.bat --no-daemon test --tests 'mp4.socllm.*' --tests 'mp4.platform.*SocLlm*' --tests 'mp4.platform.SocSuggestion*' --tests 'mp4.platform.MaintainerSocLlm*' --tests 'mp4.platform.ReportClarify*' --tests mp4.auth.ReportClarifyAuthenticatedIntegrationTest`
   Exit 0, BUILD SUCCESSFUL. Output: `C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/rc-g2b-green/regression.txt`

   | Class | Tests | Failures | Errors | Skipped |
   |---|---|---|---|---|
   | mp4.auth.ReportClarifyAuthenticatedIntegrationTest | 5 | 0 | 0 | 0 |
   | mp4.platform.MaintainerSocLlmConfigurationTest | 3 | 0 | 0 | 0 |
   | mp4.platform.ReportClarifyConfigurationTest | 3 | 0 | 0 | 0 |
   | mp4.platform.ReportClarifyDropScreenedTest | 14 | 0 | 0 | 0 |
   | mp4.platform.ReportClarifyRulesTest | 24 | 0 | 0 | 0 |
   | mp4.platform.ReportClarifyScreeningHardeningTest | 9 | 0 | 0 | 0 |
   | mp4.platform.ReportClarifyServiceTest | 20 | 0 | 0 | 0 |
   | mp4.platform.SocLlmRoleConfigurationTest | 10 | 0 | 0 | 0 |
   | mp4.platform.SocLlmSuggestionProviderTest | 9 | 0 | 0 | 0 |
   | mp4.platform.SocSuggestionCredentialReflectionTest | 4 | 0 | 0 | 0 |
   | mp4.platform.SocSuggestionPromptContractTest | 1 | 0 | 0 | 0 |
   | mp4.platform.SocSuggestionProviderTest | 7 | 0 | 0 | 0 |
   | mp4.platform.SocSuggestionProviderTimeoutLifecycleTest | 2 | 0 | 0 | 0 |
   | mp4.socllm.MaintainerAdmissionAdapterTest | 2 | 0 | 0 | 0 |
   | mp4.socllm.PostgresSocLlmAdmissionTest | 7 | 0 | 0 | 0 |
   | mp4.socllm.SocLlmHttpProviderTest | 10 | 0 | 0 | 0 |
   | mp4.socllm.SocLlmRoleServiceTest | 6 | 0 | 0 | 0 |
   | mp4.socllm.SocLlmTaskPolicyTest | 7 | 0 | 0 | 0 |
   | Total | 143 | 0 | 0 | 0 |

### Hashes (SHA-256)

Changed main file:
- `src/backend/platform/src/main/java/mp4/platform/ReportClarifyRules.java` `77e721a003e9c4d0c1105fb53ad971fac07780218ae557b8fa759a73822dce88`

Unchanged main file, recorded for reference:
- `src/backend/platform/src/main/java/mp4/platform/ReportClarifyService.java` `1036a49e1380c19bcee0d58406b4ee9b2b01a7f7e42f2050047fd31109da531c`

Approved test files. All match the approval record:
- `ReportClarifyDropScreenedTest.java` `c14a442debebe510ebc07656eb421e547f643008ed53ae3a81b597add4bbb856` (match)
- `ReportClarifyRulesTest.java` `a62f53959e6613bd70e6a8f9e0eadd5516e945ee588503970bad588b45b64270` (match)
- `ReportClarifyScreeningHardeningTest.java` `6ccd86516f31e4f57413e6fa251802b584e07f86aa601b1868d4c1a81e7639d6` (match)
- `ReportClarifyServiceTest.java` `40b4487e76078d00df3ceba91b39a035106ba5ef71c5a35331e2a027638dd0ff` (match)

### Not run

Gateway, frontend (`npm run verify`), the live provider and the auth test classes outside the regression filter.
The candidate verifier and security reviewer are still pending.
