# Design

## Context

- `NewReport.tsx` (Reporter frontend) pre-fills a `###` template, enforces
  `TEXT_MAX = 20_000` and posts to `/api/reporter/reports` with CSRF from
  `/api/auth/csrf`. `/api/reporter/**` already requires the Reporter role.
- The Developer suggestion feature (`SuggestionController` / `SuggestionService` /
  `SocLlmSuggestionProvider`) is the nearest pattern: a session-freshness and actor
  check, a call to the role gateway, and strict JSON validation.
- The gateway (`socllm-per-task-limits`, merged) has these transport ceilings in
  `SocLlmHttpProvider`: 24,576 input bytes, 8,192 instruction bytes, 128 KiB request,
  64 KiB response, 30 s. `SocLlmTaskPolicy` repeats them, plus the 2,048 output-token
  ceiling. Every task uses `SocLlmTaskPolicy.initial()`.
- `SocLlmRoleConfiguration.modelForRole` already supports a Developer-only model
  override.
- The trials (`logs/reporter-report-clarify/`) found three things: default reasoning
  can use up whole budgets; restructuring loses content on long drafts; and the
  question-only shape stays small at any draft length.

## Goals / Non-Goals

**Goals:**
- Make the model's output checkable on the server: exact quotes, fixed categories,
  strict JSON.
- Raise limits only for `REPORTER_ASSIST`. Developer and Maintainer requests stay
  byte-for-byte unchanged. No role sends `reasoning_effort` (owner decision).

**Non-Goals:**
- Scoring question usefulness automatically.
- Highlighting quotes inside the editor. The results panel shows quotes as text.

## Decisions

1. **Endpoint `POST /api/reporter/report-clarify`** in `src/backend/platform`
   (`ReportClarifyController` and `ReportClarifyService`). The body is exactly
   `{title, description}`, validated with the same text rules as report creation
   (RC-03). The actor comes from the session. The service checks the account is an
   active Reporter and the session is fresh before calling the gateway (RC-02), as
   `SuggestionService` does. *Alternative:* the plain-Java `ReporterReportsHttpHandler`.
   Rejected because the gateway beans and session checks live in the platform module.

2. **Model input** is plain text,
   `Report title:\n<title>\n\nReport description (untrusted):\n<description>`, sent as
   the user message. The trusted instructions go in the system message (RC-07). The
   instructions also say:
   - the New Report template's headings (`### What happened?`,
     `### Steps to reproduce`, `### What did you expect?`) and empty placeholders
     (`1.`, blank sections) are scaffolding, never quoted as unclear parts;
   - never ask for screenshots or images, because report descriptions cannot show
     them.

   Both come from the owner's live check of a one-word draft
   (`trial-clarify-2026-11-12.md`).
   - **Question screening (RC-07b):** the server rejects a result if any question
     matches a URL (`https?://` or `www.`), an email address, or a credential request
     (password, passcode, API key, token, secret, or "login details" as a
     whole-word, case-insensitive match). This is a cheap, deterministic backstop
     against injected phishing text. **Owner decision 2026-11-13 (option b):** a
     screened question drops only its own item, so ordinary questions that trip
     the screen ("Did you clear your browser cookies?") do not fail the whole check.
     If every item is dropped, the result is a safe 502. Screening normalizes text
     first (NFKC, then removes default-ignorable and format characters, then
     lowercases), so invisible joiners cannot hide a word. Cross-script lookalikes
     and words outside the list are accepted as a known limit: results are plain
     text, shown only to the draft's author.
   - **Format rules (RC-07c)** still reject the whole result: a question with
     controls, line or paragraph separators, bidi or direction marks
     (U+200E/U+200F/U+061C), default-ignorable characters, or no visible character.

3. **Output contract** (JSON mode):
   `{"unclear":[{"quote","question"}],"missing":[{"category","question"}]}`.
   - `unclear`: 0-8 items. Each `quote` is 1-300 characters and an exact substring of
     the title or description, with no duplicates.
   - `missing`: 0-7 items. Each `category` is one of the 7 names, used at most once.
   - Every `question` is 1-300 characters on one line.
   - No other keys at any level. Any violation returns 502 `AI_INVALID_OUTPUT`
     (RC-04, RC-05).

   The server returns the validated object unchanged in shape. An empty result is
   valid (RC-05c).

4. **Rendering (RC-06).** The panel renders quotes and questions as React text nodes,
   never through safe-markdown or `dangerouslySetInnerHTML`. The draft state is never
   written by the panel. Results clear on Submit, on unmount and when a new check
   starts.

5. **No reasoning field (RC-09c, owner decision).** The gateway request shape stays
   as it is today: `model`, `messages`, `max_tokens`, `stream`, `response_format`.
   The provider's default reasoning applies. Default reasoning is unbounded, so
   decision 7 gives the Reporter task output headroom. A test pins the request keys
   so `reasoning_effort` cannot be added unnoticed.
   *Alternative considered:* a per-task `reasoning_effort` (`none`/`low`) to bound
   reasoning. Not chosen by the owner.

6. **Reporter model (RC-09).** `modelForRole` also returns
   `SOC_LLM_MODEL_REPORTER` (trimmed) for the Reporter role when it is set and
   nonblank. The intended value is `x-test-2` (owner decision). It passed 4/4 with
   default reasoning in 10.7-33.6 s. It is a provider test slot whose backing model
   can change, so task 4.2 re-checks it before live use. The validation rules do not
   depend on the model.

7. **Limits** (from `trial-clarify-2026-11-12.md`):

   | Ceiling (provider and policy) | Today | New |
   |---|---|---|
   | Input bytes | 24,576 | 61,440 (20,000 characters × 3 bytes, plus title and framing) |
   | Instruction bytes | 8,192 | 8,192 |
   | Output tokens | 2,048 | 8,192 (`x-test-2` with default reasoning used at most 2,779) |
   | Deadline | 30 s | 60 s (slowest pass was 33.6 s) |
   | Request body | 128 KiB | 384 KiB (worst-case JSON escaping of 61,440 bytes) |
   | Response body | 64 KiB | unchanged (largest seen 19.2 KB) |

   The `REPORTER_ASSIST` policy is 61,440 / 8,192 / 8,192 / 60 s / 3 starts per 5 min /
   1 in flight. Its reservation is 77,824, so `SOC_LLM_TPM` must be at least that. The Developer and Maintainer policies stay at `initial()`. Because no
   policy may exceed a ceiling, raising the ceilings changes nothing for them.

8. **Telemetry.** Record the outcome code, latency, provider token usage when present
   ("unavailable" otherwise), and the counts of unclear and missing items. Never
   record draft text, quotes or questions (RC-08).

## Risks / Trade-offs

- [Default reasoning makes a check take about 10-35 s and is unbounded] → Show a
  progress state. The 8,192-token budget and 60 s deadline turn a runaway into a safe
  `AI_INVALID_OUTPUT` or `AI_TIMEOUT`, never a partial result.
- [`x-test-2` is a test slot that can change without notice] → The model is set by
  config and checked again in task 4.2. The server-side output checks reject a worse
  model's malformed results.
- [Raising ceilings changes the in-flight `socllm-per-task-limits` TL-02] → Task 1.1
  updates that spec and its approved boundary tests through a new baseline review.
- [Questions may be unhelpful or generic] → A bounded usefulness review in task 4.2;
  output structure alone does not prove usefulness.
- [The model can change] → It is set by config. The validation rules do not depend
  on the model.

## Migration Plan

There is no data migration. Deploy with `SOC_LLM_API_KEY_REPORTER` unset to keep the
feature off. Then set the key, `SOC_LLM_MODEL_REPORTER=x-test-2` and
`SOC_LLM_TPM` of at least 77,824. To roll back, unset the Reporter key.
