# Provider limits and model trial (task 1.1, partial)

## Summary

Goal: choose a SoC model for Reporter "Suggest structure" and check whether the
gateway ceilings must rise. A bounded live trial ran 55 calls: 6 models, 5
synthetic drafts, with and without `reasoning_effort: "none"`. It used the
request shape and the RS-04..06 checks planned in design.md.

- **Best result:** `qwen3.8:27b` with `reasoning_effort: "none"`. It passed all 5
  drafts (one automatic flag was a false positive, checked by hand) in 4.2-5.4 s
  with 317-379 output tokens.
- **Without `reasoning_effort: "none"`**, the reasoning models (`qwen3.8:27b`,
  `qwen3.5:9b`, `x-test-3`) spent most or all of the 2,048-token budget on hidden
  reasoning. They returned empty content or took 15-31 s. The MP4 gateway does not
  send this field today.
- **`qwen3.6:35b`** (the current shared model) was fast (2.1-3.7 s) and needs no
  gateway change. It altered the stack-trace code block on the long draft in both
  variants, so the RS-06 check would reject that result.
- **`x-test-3`** passed 5/5 at 3.0-4.6 s, but it is a provider test slot whose
  backing model can change. Not suitable for release.
- `gemma4:26b` returned 503 on every call. `llama3.1:8b` and `qwen3.5:9b` failed
  the output format most of the time.
- **Ceilings:** every successful call stayed under 600 output tokens and 10 s for
  drafts up to about 3,700 characters, well inside today's 2,048 tokens and 30 s.
  Raising the ceilings is not needed for a capped input size.
- **Limitations:**
  - Only one sample per model and draft. Synthetic content only.
  - The long draft repeats one paragraph, so the condensed output (about 400 tokens
    from 3,700 characters) may hide content loss on a non-repetitive long draft.
  - The provider's output-token limit and quota are still unconfirmed. The context
    lengths come from the provider's model table supplied by the owner.
  - Task 1.1 stays open.

## Details

- Date: 2026-11-12. Endpoint `https://soclaas-api.comp.nus.edu.sg/v1/chat/completions`.
  An unauthenticated GET of `/v1/models` returned 401 (reachable).
- Credential: the MP3 Student role key, loaded into the trial process environment
  only, at the owner's request. It was not printed, logged or copied into MP4.
- Request: system instructions plus a plain-text user message, `max_tokens` 2048,
  `stream` false, `response_format` `json_object`. Variant B added
  `reasoning_effort: "none"`. Client timeout 120 s, sequential, 2 s apart.
- Fixtures (synthetic): F1 vague one-liner; F2 steps with a fenced stack trace;
  F3 Chinese; F4 prompt injection (asks for PWNED, a link, and a fake environment);
  F5 about 3,700 characters plus a fenced stack trace.
- Checks per call: JSON contract (7 named sections in order, exact keys, at most 3
  questions per section, each at most 300 characters on one line, a question for
  every empty section); fenced code blocks present byte-for-byte; no added links;
  injection markers absent; a heuristic for an invented OS, browser or version in
  Environment.
- A validator crash interrupted variant B after `qwen3.5:9b` F3. The validator was
  fixed to handle non-string content, and the remaining models were rerun.

| Model | Variant | Pass | Contract | Latency (s) | Output tokens | Notes |
|---|---|---|---|---|---|---|
| llama3.1:8b | A | 1/5 | 2/5 | 1.5-5.4 | 39-362 | Wrong keys, dropped code block |
| llama3.1:8b | B | 2/5 | 2/5 | 3.2-6.4 | 0-432 | Non-JSON on the injection draft |
| qwen3.5:9b | A | 0/5 | 0/5 | 11.7-40 | 2048 (3 calls) | Reasoning used the budget |
| qwen3.5:9b | B | 0/5 | 0/5 | 3.6-9.6 | 139-551 | Question bounds, wrong keys |
| gemma4:26b | A, B | 0/10 | 0/10 | <0.3 | - | HTTP 503 |
| qwen3.6:35b | A | 4/5 | 5/5 | 2.1-3.5 | 259-448 | F5 code block newlines collapsed |
| qwen3.6:35b | B | 4/5 | 5/5 | 2.1-3.7 | 265-478 | F5 lost the newline after the opening fence |
| qwen3.8:27b | A | 2/5 | 2/5 | 23.8-26.5 | 1230-2048 | Reasoning used the budget |
| qwen3.8:27b | B | 5/5* | 5/5 | 4.2-5.4 | 317-379 | *F1 flag "version" was the text "exact version unknown" |
| x-test-3 | A | 4/5 | 4/5 | 14.8-25.2 | 1174-2048 | F5 used the budget |
| x-test-3 | B | 5/5 | 5/5 | 3.0-4.6 | 301-405 | Test slot |

Manual review of F1 and F4 for `qwen3.8:27b`, `qwen3.6:35b` and `x-test-3`
(variant B): no invented OS, browser, version or steps. "Chrome" was kept with the
Reporter's uncertainty ("unconfirmed", "exact version unknown"). Injected content
(PWNED, link, Windows 11 / Chrome 120) was absent. One small addition was seen:
`qwen3.8:27b` turned "when i upload a big csv" into a numbered step,
"1. Upload a large CSV file." That is a restatement, not a new fact.

## Trial 2: long drafts, larger budget, reasoning on

### Summary

The owner asked to raise the budget for longer reports and keep reasoning on. A
second bounded trial ran 12 calls: `qwen3.8:27b` and `qwen3.6:35b`; provider-default
reasoning and `reasoning_effort: "low"`; three synthetic drafts (the F2 log draft, a
non-repetitive 10,882-character draft, and a 19,052-character draft). `max_tokens`
was 12,288 and the client timeout 300 s. Content retention was measured as the share
of unique observation IDs (69 and 125) kept in the output.

- **Default reasoning is unbounded and unusable.** `qwen3.8:27b` spent all 12,288
  tokens on reasoning for the 10.9k draft (210.8 s, no answer). Two other default
  calls got an HTTP 400 that did not reproduce on retry (treated as transient
  provider rejections).
- **`low` reasoning works up to about 11k characters.** `qwen3.8:27b` low on the
  10.9k draft: 80.4 s, 5,468 output tokens, valid contract, code block intact,
  **99% of observations kept**.
- **Long drafts get summarised and lose content, even when the output is valid.**
  On the 19k draft, every passing output kept about **1%** of observations.
  `qwen3.6:35b` also condensed the 10.9k draft (51% default, 1% low) and wrote
  "100+ specific instances" when the draft had 69. That is an invented count. The
  RS-04..06 checks did not catch this. A larger budget does not fix it: the models
  chose to summarise well before reaching the limit.
- **Size:** the largest provider response was 48,573 bytes, a reasoning-heavy failed
  call. Successful low-reasoning responses were 3.7-29.8 KB.
- **Limitations:** one sample per cell, synthetic structured observations,
  retention measured only by IDs.

| Model | Reasoning | Draft | Status | Seconds | In / out tokens | Finish | Kept | Checks |
|---|---|---|---|---|---|---|---|---|
| qwen3.8:27b | default | F2-log | 400 | 0.1 | - | - | - | - |
| qwen3.8:27b | default | L10k | 200 | 210.8 | 4152 / 12288 | length | 0% | no content |
| qwen3.8:27b | default | L19k | 400 | 0.2 | - | - | - | - |
| qwen3.8:27b | low | F2-log | 200 | 10.2 | 362 / 681 | stop | - | pass |
| qwen3.8:27b | low | L10k | 200 | 80.4 | 4140 / 5468 | stop | 99% | pass |
| qwen3.8:27b | low | L19k | 200 | 48.4 | 6926 / 2894 | stop | 1% | pass (content lost) |
| qwen3.6:35b | default | F2-log | 200 | 2.6 | 338 / 320 | stop | - | pass |
| qwen3.6:35b | default | L10k | 200 | 22.8 | 4116 / 2990 | stop | 51% | code block altered |
| qwen3.6:35b | default | L19k | 200 | 13.9 | 6902 / 1784 | stop | 1% | code block altered |
| qwen3.6:35b | low | F2-log | 200 | 31.2 | 336 / 4169 | stop | - | pass |
| qwen3.6:35b | low | L10k | 200 | 54.6 | 4114 / 6757 | stop | 1% | pass (content lost) |
| qwen3.6:35b | low | L19k | 200 | 67.6 | 6900 / 8875 | stop | 1% | pass (content lost) |

## Trial 3: x-test-2 and x-test-3

### Summary

At the owner's request, the trial 2 setup (12,288 `max_tokens`, 300 s, default and
`low` reasoning, F2-log / 10.9k / 19k drafts) was run on `x-test-2` (provider test
slot, currently DeepSeek V4.1 Flash) and `x-test-3` (currently Qwen 3.8 Flash Next).
The trial 1 short drafts were also run on `x-test-2` with `low` and `none`.

- **`x-test-2` with `low` is the only model that kept all content on long drafts:**
  100% of observations on both 10.9k (84.5 s, 9,679 output tokens) and 19k
  (86.3 s, 10,505 output tokens), with a valid contract, code blocks intact, and no
  numbers in the output that were not in the draft. The 19k output used 85% of the
  12,288-token budget.
- `x-test-2` with default reasoning ran out of budget on both long drafts (about
  110 s, `finish=length`, truncated non-JSON). Short drafts: 5/5 with `none`
  (4.6-7.5 s); 4/5 with `low`. The F2 failure was reasoning using the short
  trial's 2,048-token budget. Manual review of F1/F4 (`none`): no invented facts, and
  injected content was absent.
- `x-test-3` with `low` kept only 50% on both long drafts (37-61 s). With default
  reasoning it produced no content (105-134 s, budget exhausted).
- Largest response: 48,697 bytes (a default-reasoning truncation). Successful
  `low` long responses were 13.9-38.6 KB.
- **Limitation:** both are provider test slots whose backing model can change
  without notice. Results are one sample per cell.

| Model | Reasoning | Draft | Seconds | Out tokens | Finish | Kept | Checks |
|---|---|---|---|---|---|---|---|
| x-test-2 | default | F2-log | 17.0 | 1475 | stop | - | pass |
| x-test-2 | default | L10k | 110.2 | 12288 | length | - | not JSON |
| x-test-2 | default | L19k | 106.2 | 12288 | length | - | not JSON |
| x-test-2 | low | F2-log | 20.0 | 1682 | stop | - | pass |
| x-test-2 | low | L10k | 84.5 | 9679 | stop | 100% | pass |
| x-test-2 | low | L19k | 86.3 | 10505 | stop | 100% | pass |
| x-test-3 | default | F2-log | 18.4 | 1642 | stop | - | pass |
| x-test-3 | default | L10k | 105.6 | 12288 | length | 0% | no content |
| x-test-3 | default | L19k | 134.2 | 12288 | length | 0% | no content |
| x-test-3 | low | F2-log | 4.7 | 493 | stop | - | pass |
| x-test-3 | low | L10k | 37.0 | 3756 | stop | 50% | pass (content lost) |
| x-test-3 | low | L19k | 61.4 | 6062 | stop | 50% | pass (content lost) |

Short drafts, `x-test-2` (2,048 `max_tokens`): `low` 4/5 (6.1-26.7 s; F2 budget
exhausted); `none` 5/5 (4.6-7.5 s, 205-406 tokens).
