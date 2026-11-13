# Red log: reporter-report-clarify group 2 (tasks 2.1, 2.2, 2.3)

## Summary

Goal: write failing tests for the Reporter check endpoint `POST /api/reporter/report-clarify`: who may call it, input limits, the model's prompt and output rules, safe failures and logs.
Changes: three new test files and this log. No application code was changed.
Checks and results: the control run of `SocLlmSuggestionProviderTest` passes when the new tests are set aside (BUILD SUCCESSFUL). The focused Red run fails at test compilation, and the only unresolved symbols are the new ReportClarify types (`ReportClarifyRules`, `ReportClarifyService`, `SocLlmReportClarifyProvider`, `ReportClarifyProvider`). No setup, import or typo errors remain.
BLOCKED: `ReportClarifyAuthenticatedIntegrationTest` cannot run here. It needs `MP4_AUTH_TEST_JDBC_URL`, `MP4_AUTH_TEST_DB_USER` and `MP4_AUTH_TEST_DB_PASSWORD` for a disposable PostgreSQL; PostgreSQL tools are not installed. It was only compile-checked (same missing-symbol failure). RC-02a/b are therefore covered by an unrun test; the unit tests cover only that a missing actor and every refusal reach the gateway zero times.
Limitations: the tests pin an assumed API (below) that the feature owner should confirm. Admission "not used" is shown only by zero provider calls, because no admission fake exists at this layer. Reviewer-visible log hygiene is checked on the `ReportClarifyService` logger only.

## Revision

HEAD 1b2d3b7 (branch llm-reporter) plus uncommitted group 1 candidate and `openspec/changes/reporter-report-clarify/`.

## Test files (SHA-256)

- `src/backend/auth/src/test/java/mp4/platform/ReportClarifyRulesTest.java` a62f53959e6613bd70e6a8f9e0eadd5516e945ee588503970bad588b45b64270
- `src/backend/auth/src/test/java/mp4/platform/ReportClarifyServiceTest.java` 40b4487e76078d00df3ceba91b39a035106ba5ef71c5a35331e2a027638dd0ff
- `src/backend/auth/src/test/java/mp4/auth/ReportClarifyAuthenticatedIntegrationTest.java` ba130901c974d7473c128fc0f7abcc1b57aaa9b86d733f8467cedfcac55267c5

## Requirements quoted (spec.md)

- RC-02: "The server SHALL accept a check only from an authenticated, active Reporter session with a valid CSRF token. Any other caller SHALL be refused before the model is called, with a safe error that contains none of the submitted text."
- RC-03: "A request SHALL contain exactly the draft `title` and `description`. They SHALL meet the same text rules and limits as report creation (title 1-200 characters on one line, description 1-20,000 characters, no NUL or lone surrogates). Invalid or oversized input SHALL be refused before the model is called."
- RC-04: "Each unclear part SHALL have a quote of 1-300 characters that appears exactly in the draft title or description, and one question. A result SHALL have at most 8 unclear parts with no duplicate quotes. Model output with a quote that is not an exact substring of the draft SHALL be rejected as invalid."
- RC-05: "Each missing item SHALL name one category from: Summary, Steps to reproduce, Expected behavior, Actual behavior, Environment, Frequency, Error messages and logs. Each category SHALL appear at most once, with one question. Unknown or duplicate categories, extra keys, wrong types or questions outside 1-300 characters on one line SHALL make the result invalid."
- RC-07: "The draft title and description SHALL be treated as untrusted data in a message separate from the trusted instructions. Instructions inside the draft SHALL NOT be followed. Output is accepted only if it passes RC-04 and RC-05." RC-07b: any question with a URL, email address or credential request rejects the whole result.
- RC-08: "Requests SHALL use the `REPORTER_ASSIST` task policy and the shared per-key budgets. Timeout, rate limit, busy, unavailable and invalid output SHALL return safe error codes with the draft unchanged. Logs SHALL NOT contain the draft text, quotes or questions."

## Assumed API surface (new production types, package `mp4.platform`, module platform)

- `final class ReportClarifyRules` (package-private ok)
  - `record Draft(String title, String description)`
  - `static Draft parseRequest(byte[] body)` throws `IllegalArgumentException` (message never contains submitted text); exact keys, strict JSON, duplicate keys refused, report-creation text rules.
  - `static String instructions()` (trusted system text, <= 8,192 bytes)
  - `static String modelInput(Draft)` returns exactly `Report title:\n<title>\n\nReport description (untrusted):\n<description>`
  - `static Result validateOutput(String raw, Draft draft)` throws `IllegalArgumentException`; `Result` has `List<Unclear> unclear()`, `List<Missing> missing()`, `String toJson()`; `record Unclear(String quote, String question)`, `record Missing(String category, String question)`.
- `public interface ReportClarifyProvider { String clarify(UUID actorId, String title, String description); String modelName(); }` returns raw model text, and throws a provider failure for gateway errors.
- `final class SocLlmReportClarifyProvider implements ReportClarifyProvider` with `SocLlmReportClarifyProvider(SocLlmContracts.RoleGateway gateway, String model)` (throws `IllegalArgumentException` on null gateway or blank model); builds `Call(Role.REPORTER, actor, Task.REPORTER_ASSIST, instructions, modelInput, 8192, 60 s)`.
- `final class ReportClarifyService` with `ReportClarifyService(ReportClarifyProvider providerOrNull)`, `Reply handle(UUID actorId, byte[] body)`, and `record Reply(int status, String code, Integer retryAfterSeconds, String body)`. 200 returns the result JSON; 400 `VALIDATION_FAILED`; 502 `AI_INVALID_OUTPUT`; 409 `AI_BUSY`; 429 `AI_RATE_LIMITED` with `retryAfterSeconds`; 503 `AI_UNAVAILABLE` (also when provider is null); 504 `AI_TIMEOUT`; null actor gives 401 or 403. Gateway `SummaryFailure` codes are mapped (busy 409, rate limited 429, unavailable 503, timeout 504, invalid output 502); other runtime exceptions give 503.
- Telemetry on the `ReportClarifyService` logger: one line per request containing `outcome=<success|invalid_output|timeout|rate_limited|...>`, `duration_ms=`, `input_tokens=unavailable` when usage is absent, `unclear_count=`, `missing_count=` and no draft, quote or question text.
- Controller (not unit-tested here): `POST /api/reporter/report-clarify`, Reporter role and CSRF enforced by the existing security chain; the integration test supplies a `@Primary ReportClarifyProvider` bean.

## Scenario to test

| Scenario | Test |
|---|---|
| RC-02a wrong caller | `ReportClarifyAuthenticatedIntegrationTest.rc02a_unauthenticatedCallerIsRefusedWithoutProvider`, `rc02a_developerAndMaintainerAreRefusedWithoutProvider`, `rc02a_inactiveReporterIsRefusedWithoutProvider` (BLOCKED, no PostgreSQL); `ReportClarifyServiceTest.rc02_missingActorIsRefusedBeforeGateway` |
| RC-02b missing CSRF | `ReportClarifyAuthenticatedIntegrationTest.rc02b_missingOrWrongCsrfTokenIsRefusedWithoutProvider` (BLOCKED); positive control `rc02_positiveControlActiveReporterWithCsrfIsServed` (BLOCKED) |
| RC-03a oversized | `ReportClarifyRulesTest.rc03a_refusesDescriptionOf20001Characters`; `ReportClarifyServiceTest.rc03a_oversizedDescriptionRefusedWithoutGatewayOrEcho` |
| RC-03b extra fields | `ReportClarifyRulesTest.rc03b_refusesExtraMissingAndNonStringKeys`; `ReportClarifyServiceTest.rc03b_extraKeyRefusedWithoutGateway`, `rc03_malformedBodyRefusedWithoutGateway` |
| RC-03c full length | `ReportClarifyRulesTest.rc03c_acceptsDescriptionOfExactly20000ThreeByteCharacters`; `ReportClarifyServiceTest.rc03c_fullLengthThreeByteDraftIsSentUnderReporterPolicy` |
| RC-03 text rules | `ReportClarifyRulesTest.rc03_titleRules`, `rc03_descriptionRules`, `rc03_refusalMessageDoesNotEchoSubmittedText` |
| RC-04a | `ReportClarifyRulesTest.rc04a_*`, `rc04_quoteAndCountBounds`, `rc04_duplicateQuotesRejected`, `rc04_resultJsonKeepsShape`; `ReportClarifyServiceTest.rc04a_validResultIsReturnedAsPlainJsonObject` |
| RC-04b | `ReportClarifyRulesTest.rc04b_inventedOrInexactQuoteRejectsWholeResult`; `ReportClarifyServiceTest.rc04b_inventedQuoteGives502InvalidOutputWithoutDraftText`, `rc04_nullOrOversizedGatewayOutputGives502` |
| RC-05a | `ReportClarifyRulesTest.rc05a_missingEnvironmentIsListed`, `rc05_allSevenCategoriesAccepted`, `rc05_questionBounds` |
| RC-05b | `ReportClarifyRulesTest.rc05b_unknownOrDuplicateCategoryRejected`, `rc05_structuralViolationsRejected`; `ReportClarifyServiceTest.rc05b_unknownCategoryGives502InvalidOutput` |
| RC-05c | `ReportClarifyRulesTest.rc05c_emptyResultIsValid`; `ReportClarifyServiceTest.rc05c_emptyResultIsA200WithEmptyLists` |
| RC-07a injection | `ReportClarifyRulesTest.rc07_inputFramesDraftAsUntrustedUserMessage`, `rc07_instructionsCoverUntrustedScaffoldingAndNoScreenshots`, `rc07a_instructionInDraftDoesNotRelaxValidation`; `ReportClarifyServiceTest.rc07_callUsesReporterRoleTaskActorAndSeparatedMessages` |
| RC-07b screening | `ReportClarifyRulesTest.rc07b_urlEmailAndCredentialQuestionsRejectWholeResult`, `rc07b_credentialTermsMatchWholeWordsOnly`; `ReportClarifyServiceTest.rc07b_urlQuestionGives502InvalidOutput` |
| RC-08a failures and rate limit | `ReportClarifyServiceTest.rc08_gatewayFailuresMapToSafeCodes`, `rc08a_rateLimitedCarriesRetryAfter`, `rc08_unexpectedGatewayExceptionIsUnavailableAndDoesNotLeakMessage` |
| RC-08b unavailable | `ReportClarifyServiceTest.rc08b_noProviderMeansUnavailableWithoutGateway` |
| RC-08 logs and telemetry | `ReportClarifyServiceTest.rc08_successLogRecordsCountsAndOutcomeButNoDraftOrResultText`, `rc08_failureLogsRecordOutcomeWithoutDraftOrOutputText`, `rc08_refusalLogHasNoSubmittedText`; fixture: the owner's one-word draft (`Button` / `### What happened?\nbutton`) |

## Commands and results

Scratch: `C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/rc-g2` (`control.txt`, `control-clean.txt`, `red.txt`, `integration.txt`).

1. Control, with the new tests set aside: `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests mp4.platform.SocLlmSuggestionProviderTest` gives BUILD SUCCESSFUL (`control-clean.txt`). With the new tests present it fails only because they do not compile (`control.txt`), which is expected during Red.
2. Focused Red: `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests 'mp4.platform.ReportClarify*'` gives `compileTestJava` FAILED: `cannot find symbol` for `ReportClarifyRules`, `ReportClarifyService`, `SocLlmReportClarifyProvider` only (`red.txt`).
3. Integration: `--tests mp4.auth.ReportClarifyAuthenticatedIntegrationTest` fails the same compile (`ReportClarifyProvider`); BLOCKED for execution on `MP4_AUTH_TEST_JDBC_URL`, `MP4_AUTH_TEST_DB_USER`, `MP4_AUTH_TEST_DB_PASSWORD` (disposable PostgreSQL).

No agent coordination beyond this specialist; context supplied: handoff packet, spec, design, existing Suggestion classes and tests.
