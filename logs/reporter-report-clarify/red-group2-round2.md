# Red evidence: reporter-report-clarify task 2.4 (group 2, round 2)

## Summary and scope

Goal: the owner decided (2026-11-13, option b) that a question failing the URL, email or credential
screen drops only its own item instead of failing the whole check, and that hidden-character rules
(RC-07c) apply. If every item is dropped the check must still fail safely (502 AI_INVALID_OUTPUT).

What changed: one new test file, ReportClarifyDropScreenedTest, with 14 tests. No application code and no
previously approved assertion was changed. The existing RC-07b assertions in ReportClarifyRulesTest and
ReportClarifyScreeningHardeningTest each use a result with a single item, so under the drop rule that
item is dropped, every item is dropped, and the check still fails. They remain correct and still pass.

Result: Red. 70 tests ran, 10 failed, all in the new file. The 4 other new tests pass because they pin
behavior that already holds (all-dropped rejection, empty result valid, control characters, unicode
questions) and guard against regressions. Remaining limit: Cyrillic and cross-script lookalikes are out of scope.

## Technical evidence

- Role: test_designer. Task: reporter-report-clarify/2.4. Model and effort: claude-sonnet-5-5, effort not exposed; token use unavailable.
- Revision: HEAD 1b2d3b7 (branch llm-reporter) plus uncommitted groups 1-3.
- Context: spec RC-07b/RC-07c, design Decision 2, ReportClarifyRules.java and the three existing tests.
- Setup control (before edits): `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests 'mp4.platform.ReportClarify*'` passed (BUILD SUCCESSFUL), output in scratchpad rc-g2b/control.txt.
- Focused Red (same command): 70 tests, 10 failed, BUILD FAILED. Output: C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/rc-g2b/red.txt
- Failing tests:
  - Drop rule fails because validateOutput throws IllegalArgumentException (whole-result rejection): rc07b_screenedUnclearQuestionDropsOnlyItsItem, rc07b_screenedMissingQuestionDropsOnlyItsItem, rc07b_dropsAcrossBothListsKeepingValidOnesInOrder, rc07b_cookiesAnd2faQuestionsNoLongerFailResultWithOtherValidItems, rc07b_droppedItemsDoNotAppearInResultJson, rc07b_ordinaryWordsAreKeptWhileCredentialRequestIsDropped.
  - Hidden characters fail because the result is accepted (assertThrows finds no exception): rc07c_securityReviewerBypassStringsDoNotSurvive (pass U+034F word, to U+FE0F ken), rc07c_defaultIgnorableAndFormatCharactersRejectWholeResult, rc07c_directionMarksRejectWholeResult, rc07c_questionWithNoVisibleCharacterRejectsWholeResult.
- Passing new tests (current behavior already matches): rc07b_everyItemDroppedIsInvalidOutputNotAnEmptyResult, rc07b_genuinelyEmptyResultStaysValid, rc07c_controlsSeparatorsAndBidiControlsStillRejectWholeResult, rc07c_ordinaryQuestionsWithValidItemsAreKept.
- Changed previously approved assertions: none (see summary).
- SHA-256 of test files:
  - ReportClarifyDropScreenedTest.java c14a442debebe510ebc07656eb421e547f643008ed53ae3a81b597add4bbb856 (new)
  - ReportClarifyRulesTest.java a62f53959e6613bd70e6a8f9e0eadd5516e945ee588503970bad588b45b64270 (unchanged)
  - ReportClarifyScreeningHardeningTest.java 6ccd86516f31e4f57413e6fa251802b584e07f86aa601b1868d4c1a81e7639d6 (unchanged)
- Checks not run: gateway, frontend, live provider, other test classes.
