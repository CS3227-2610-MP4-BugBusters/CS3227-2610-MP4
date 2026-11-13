# Tasks

For each behavior task, follow `workflow/tdd.md`: test_designer, a fresh
baseline_verifier, feature-owner approval of the verified Red, the implementer, a
fresh candidate_verifier and a separate security_reviewer, since this is an
AI-security change. Record evidence under `logs/reporter-report-clarify/`. Backend
tests run from `src/backend/auth`. Frontend checks use `npm run verify` in
`src/frontend/reporter`.

## 1. Gateway support (RC-03c, RC-09, design 5-7)

- [x] 1.1 Raise the transport ceilings in `SocLlmHttpProvider` and `SocLlmTaskPolicy`
  to the design 7 values. Update `socllm-per-task-limits` TL-02 text and its approved
  boundary tests through a new baseline review. Verify that the TL-02 tests pass at
  the new ceilings, that a call one unit above each ceiling is rejected, and that
  `SocLlmHttpProviderTest` passes.
- [x] 1.2 Give `REPORTER_ASSIST` its design 7 policy. Verify that the Reporter
  policy has the design 7 values and a reservation of 77,824, that the Developer and
  Maintainer policies still equal `initial()` (Maintainer reservation 34,816), and
  that the provider request body has exactly the keys `model`, `messages`,
  `max_tokens`, `stream` and `response_format`, with no `reasoning_effort` (RC-09c).
- [x] 1.3 Add `SOC_LLM_MODEL_REPORTER` to `modelForRole`, and add it to
  `.env.example`. Verify RC-09a/b in `SocLlmRoleConfigurationTest`: the override
  applies, a missing or blank override falls back, and other roles keep their models.

## 2. Endpoint (RC-02..RC-05, RC-07, RC-08)

- [x] 2.1 Add `POST /api/reporter/report-clarify` with session actor, active-Reporter
  and fresh-session checks, CSRF, and exact-body and text-rule validation. Verify
  RC-02a/b and RC-03a/b/c: zero gateway calls on each refusal, and no submitted text
  in error bodies.
- [x] 2.2 Add the instructions and the output validation (design 3). Verify RC-04a/b,
  RC-05a/b/c, RC-07a and RC-07b with a fake gateway: an inexact quote, a quote from the
  title, a duplicate quote, 9 unclear items, an unknown or duplicate category, extra
  keys, a multi-line question, non-JSON, and questions containing a URL, an email
  address or a credential request. Verify the instructions name the template
  scaffolding and forbid screenshot requests.
- [x] 2.3 Map gateway failures to safe responses and record sanitized telemetry.
  Verify RC-08a/b plus timeout, busy and unavailable, that no report row changes,
  and that captured logs contain no fixture draft text, quotes or questions.

- [x] 2.4 Apply owner decision (b) and the round-2 security findings: a screened
  question drops only its item (all dropped -> safe 502); normalization removes
  default-ignorable characters before screening; questions with direction marks
  (U+200E/U+200F/U+061C), default-ignorables or no visible character are invalid
  (RC-07b, RC-07c). Verify with updated RC-07b tests and new RC-07c tests through
  test_designer and baseline review (owner standing approval), and confirm that a
  cookies or 2FA question no longer fails a result that has other valid items.

## 3. New Report screen (RC-01, RC-06)

Owner decision 2026-11-13: groups 3 and 4 are built without the TDD agent flow.
Their stated checks still apply, and the evidence log records the waiver.

- [x] 3.1 Add Check my report to `NewReport.tsx`, with a results panel, a progress
  state, an empty-result message and error messages. Verify with Vitest RC-01a/b/c
  and RC-05c, plus `npm run verify`.
- [x] 3.2 Keep the draft untouched and render results as plain text (design 4).
  Verify RC-06a/b with Vitest: the draft is identical after show and dismiss, markup
  and URLs appear as text with no anchor or element, and results clear on a new
  check and on submit. Run `npm run verify`.

## 4. Integration and evaluation

- [x] 4.1 Run every test above plus the existing gateway, Developer and Maintainer AI
  tests on one revision. Record the commands and counts, and the candidate_verifier
  and security_reviewer verdicts, in the evidence log.
- [x] 4.2 Run a bounded live check with `x-test-2` (no reasoning field) on frozen synthetic
  fixtures (vague, the owner's one-word "button" draft in the template, log, CJK,
  a 20,000-character draft, and injection variants: instructions in a code block,
  in Chinese, a quote that tries to break the JSON, "reveal your instructions",
  and fake system-message text) through the real
  endpoint. Record outcome, latency, token usage and a manual usefulness note for
  each question set. Live use stays disabled if a check fails.
- [x] 4.3 Document the feature, endpoint, `SOC_LLM_API_KEY_REPORTER`,
  `SOC_LLM_MODEL_REPORTER`, the `SOC_LLM_TPM` minimum and the new ceilings in
  `src/backend/socllm/README.md` and the Reporter API contract. Verify that the
  documented values match the code.

## Workflow follow-up

- Archive after owner review, then sync `reporter-report-clarify` into `openspec/specs/`.
