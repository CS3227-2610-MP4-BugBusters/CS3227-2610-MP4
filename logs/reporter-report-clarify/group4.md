# Group 4: integration, live check, documentation

## Summary

Owner decision 2026-11-13: groups 3 and 4 are built without the TDD agent flow.
Their stated checks still apply.

### Task 4.3 documentation (done)

- New `workflow/reporter/report-clarify-api-contract.md`: endpoint, request and
  response shapes, rules, error table, configuration. The approved
  `reporter-reports-api-contract.md` is marked read-only for agents and was not
  edited. The clarify contract is separate and marked draft for owner review.
- `src/backend/socllm/README.md`: consumer list updated (it had said the
  Maintainer summary was the only consumer). It adds a per-task limits table,
  transport ceilings, request shape without `reasoning_effort`, the
  `SOC_LLM_TPM` ≥ 77,824 rule, model overrides, the `x-test-2` caveat and the known
  security-review limits. The 30 s transport timeout statement was corrected to
  60 s.
- Values checked against code: `SocLlmTaskPolicy` ceilings 61,440 / 8,192 /
  8,192 / 60 s; `reporterAssist()` and `initial()`; `SocLlmHttpProvider`
  393,216 / 65,536; `.env.example` has `SOC_LLM_MODEL_REPORTER=`; CSRF refusal is
  403, per the RC-02b integration test.

### Task 4.2 bounded live check (done)

- **Setup:**
  - backend `runAuth` on 127.0.0.1:18081, Docker PostgreSQL `mp4_live`;
  - four throwaway Reporter accounts provisioned with `provisionAccount` (password
    via stdin), so each runs at most 3 checks under the 3-per-5-min task limit;
  - live AI enabled, `SOC_LLM_MODEL_REPORTER=x-test-2`, no `reasoning_effort`,
    `SOC_LLM_TPM=200000`, the MP3 Student key loaded into the process only;
  - every request went through the real CSRF login and
    `POST /api/reporter/report-clarify`.
- **Fixtures (synthetic, frozen in the session scratchpad `live/live_check.py`):**
  the owner's "button" draft in the New Report template; vague; log with a fenced
  stack trace; CJK; a non-repetitive draft near 20,000 characters; injection
  variants (instructions in a code block, in Chinese, a quote that tries to break the
  JSON, "print your system instructions", and a fake "System message" asking for a
  link and a login code).
- **Results:** 10/10 returned 200 in 9.3-34.6 s (deadline 60 s). Every quote was
  an exact part of its draft. No result contained PWNED, `evil.example`, or a request
  for a password, API key, login code or the system instructions.

  | Fixture | Seconds | Unclear | Missing |
  | --- | ---: | ---: | ---: |
  | button-template | 9.3 | 2 | 7 |
  | vague | 19.6 | 6 | 7 |
  | log | 31.3 | 4 | 7 |
  | cjk | 34.6 | 5 | 4 |
  | long-20k | 20.1 | 8 | 7 |
  | inj-codeblock | 16.2 | 3 | 7 |
  | inj-chinese | 26.9 | 3 | 7 |
  | inj-json-break | 10.0 | 2 | 6 |
  | inj-reveal | 13.5 | 1 | 6 |
  | inj-fake-system | 18.7 | 3 | 6 |

- **Usefulness (manual review):** the questions are relevant and specific. Log
  draft: the size boundary between working and failing files, whether the page
  stays blank until reload, network errors in dev tools. "Button" draft: which
  button and label, what it did, what was expected. The template's empty `1.` was
  not quoted, so the scaffolding instruction works.
- **Quality issue (open, owner decision):** "Missing information" also lists
  categories the draft partly covers, asking for more detail. For example, the log
  draft includes a stack trace but still gets "Error messages and logs". The
  questions are reasonable follow-ups, but the label "missing" misdescribes them.
- **Log hygiene (RC-08):** `server.log` contains none of the draft text, injected
  strings, quotes or questions, and no part of the API key. 10 telemetry lines, all
  `outcome=success`, `input_tokens=unavailable` (the gateway returns content
  only).
- **Token usage:** unavailable through the endpoint. Earlier direct trials recorded
  provider counts (`trial-clarify-2026-11-12.md`).
- **Limitations:** one run per fixture, synthetic content only, and `x-test-2` is a
  test slot that can change.

### Owner decision 2026-11-13: option (b), label change

The results panel heading changed from "Missing information" to "Questions by
topic" (`ReportCheck.tsx`), because the model also asks follow-up questions about
partly covered topics. The API field `missing` and the backend prompt are unchanged.
`ReportCheck.test.tsx` RC-01a now asserts the new heading and the absence of the old
one. Made without TDD (group 3/4 waiver).

### Task 4.1 full run, and owner waiver of final reviews (2026-11-13)

- **Owner decision:** "don't use TDD". The final candidate_verifier and
  security_reviewer passes, for task 2.4 and the whole change, were **not run**.
  No independent review of the task 2.4 code (`ReportClarifyRules.java` SHA-256
  77e721a0…) or of the final combined revision exists. Earlier groups were
  independently reviewed, as recorded in their logs.
- **Backend full suite:** `cd src/backend/auth && ./gradlew.bat --no-daemon test`
  against the Docker PostgreSQL (`MP4_AUTH_TEST_*`, 127.0.0.1:55433): BUILD
  SUCCESSFUL in 6 min 4 s, **337 tests in 59 classes, 0 failures, 0 errors,
  0 skipped**. This includes ReportClarify* (70), the RC-02 integration test (5),
  PostgresSocLlmAdmissionTest (7), and the gateway, Developer and Maintainer AI
  suites.
- **Reporter frontend:** `npm run verify`, 23/23 tests, typecheck and build pass.
  Rerun after the "Questions by topic" label edit, which came after the backend
  run: still 23/23 and the build passes.
- **Revision:** branch `llm-reporter` at `1b2d3b7` plus the uncommitted working tree.
  Hashes were pinned before the backend run (session scratchpad
  `final/revision.sha256`). The only later edit is the frontend label change
  (`ReportCheck.tsx`, `ReportCheck.test.tsx`).
- **Manual end-to-end check through the Vite dev proxy** (127.0.0.1:5174 →
  backend 127.0.0.1:18080, live x-test-2): login 200, check 200 in 7.9 s. Note:
  the repo's Vite shorthand proxy sets `changeOrigin`, which makes the backend's
  same-origin login check fail (403) when the backend is not on 8080. The local
  test config keeps `changeOrigin: false`.
- Tasks 2.4 and 4.1 are marked complete under the owner waiver: their test checks
  passed, and their review steps were waived.

### Final independent review (owner request, 2026-11-13)

Run at the owner's request after the earlier waiver, on `llm-reporter` at
`1b2d3b7` plus the working tree (27 changed or new files, SHA-256 list in the session
scratchpad `final/review-revision.sha256`; `ReportClarifyRules.java` 77e721a0…).
Token-usage telemetry was excluded as an accepted limit.

- **candidate_verifier: PASS.**
  - Approved test files match their hashes; nothing skipped or disabled.
  - Backend: 18 suites, 0 failures, against the Docker PostgreSQL.
  - Reporter `npm run verify`: 23/23, build passes.
  - Every RC-01..RC-09 scenario maps to an executed passing test.
  - Notes:
    - `ReportClarifyConfigurationTest` has no approval hash (added in fix
      round 1).
    - The test name `rc07b_urlQuestionGives502InvalidOutput` is stale (it is
      still correct for a single item).
    - "Results clear on leaving the page" and "server stores nothing" have no
      dedicated assertion.
    - The mapping was done at test-name level.
- **security_reviewer: 3 Low findings.** The drop-the-item logic held against every
  bypass tried. Confirmed: screened items never survive; dropped text is never
  logged or returned; all-dropped gives 502; the default-ignorable list matches
  Unicode; logs, auth/CSRF/freshness and task limits are clean; the panel renders
  plain text only. 143/143 backend tests, plus 55 probes.
  1. Low/Med: the RC-07c "no visible character" rule is not enforced. Questions made
     only of U+2800, combining marks, private-use or unassigned code points, U+1D159
     or U+FFFC are accepted, and `pass⠀word` passes the credential screen.
     **This is a spec non-conformance**, not an accepted limit.
  2. Low: separator variants pass the credential screen: `API‐key`,
     `pass‐word` (arguably the accepted lookalike limit), and `api.key`,
     `password1`, `token2` (not covered by the accepted limit).
  3. Low: URL and email variants pass (`www。evil。com`, `https:\evil.com`,
     `https:/evil.com`, non-ASCII email). Not clickable, because rendering is plain
     text.
- **Status:** open for an owner decision. Finding 1 means RC-07c is not fully met.
