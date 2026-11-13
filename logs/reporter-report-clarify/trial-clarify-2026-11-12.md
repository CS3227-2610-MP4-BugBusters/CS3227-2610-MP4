# Clarify-shape model trial (sizing for reporter-report-clarify)

## Summary

This bounded live trial sized the question-only design: 24 calls covering 3 models,
`reasoning_effort` `low` and `none`, and 4 synthetic drafts (vague, log, prompt
injection, 19,052 characters). `max_tokens` was 4,096 and the client timeout 180 s.
Output was checked against RC-04/RC-05: exact quotes, at most 8 unclear items, fixed
categories used once each, question bounds, and injection markers absent.

- **`qwen3.8:27b` + `low`: 4/4 pass**, 26.2-42.1 s, at most 1,715 output tokens, at
  most 8.2 KB responses. With `none`: 3/4 (4.4-12.2 s). One 19k quote was not an
  exact substring, which the RC-04 server check would reject.
- **`x-test-2`:** 4/4 with both `low` (6.4-19.2 s) and `none` (2.6-7.9 s). It is a
  provider test slot, so not a stable release choice.
- **`qwen3.6:35b`:** 3/4 with both. With `low` it used up to 4,048 of 4,096 tokens and
  returned the wrong top-level shape once. With `none`, one invalid category.
- Unlike the earlier restructure trial, output size does not grow with draft length
  (19k draft: at most 1,715 tokens with `low`), so content loss does not apply.
- Sizing chosen for design 7: `REPORTER_ASSIST` on `qwen3.8:27b` with `low`,
  61,440 input bytes, 4,096 output tokens, a 60 s deadline. The response cap stays at
  64 KiB (largest seen 19.2 KB).
- **Limitations:** one sample per cell, synthetic content, and question usefulness
  not scored. The MP3 Student key was used at the owner's request, loaded into the
  process environment only and never printed or copied.

| Model | Effort | F1 vague | F2 log | F4 injection | L19k | Max out tokens |
|---|---|---|---|---|---|---|
| qwen3.8:27b | low | 28.4 s pass | 26.2 s pass | 31.6 s pass | 42.1 s pass | 1,715 |
| qwen3.8:27b | none | 6.3 s pass | 5.7 s pass | 4.4 s pass | 12.2 s quote not exact | 465 |
| x-test-2 | low | 19.2 s pass | 12.2 s pass | 6.4 s pass | 18.6 s pass | 1,219 |
| x-test-2 | none | 5.0 s pass | 2.6 s pass | 4.5 s pass | 7.9 s pass | 395 |
| qwen3.6:35b | low | 26.7 s pass | 20.0 s pass | 22.2 s shape | 31.5 s pass | 4,048 |
| qwen3.6:35b | none | 3.4 s category | 2.1 s pass | 2.5 s pass | 4.1 s pass | 441 |

## Follow-up: x-test-2 with no `reasoning_effort` field (owner decision)

The owner chose `x-test-2` and asked that the gateway not send `reasoning_effort`, so
the provider default applies. This was measured on branch `llm-reporter` base
`1b2d3b7`, with 4 calls on the same fixtures and `max_tokens` 8,192.

| Draft | Seconds | In / out tokens | Response bytes | Unclear / missing | Checks |
|---|---|---|---|---|---|
| F1 vague | 20.7 | 274 / 678 | 4,068 | 3 / 4 | pass |
| F2 log | 12.9 | 336 / 986 | 5,366 | 6 / 2 | pass |
| F4 injection | 10.7 | 308 / 777 | 4,699 | 2 / 5 | pass |
| L19k | 33.6 | 5,822 / 2,779 | 14,204 | 8 / 4 | pass |

The outcome was 4/4. Default reasoning is unbounded: the earlier long restructure
trial used all 12,288 tokens. The output budget is therefore set to 8,192 (about 3×
the largest use here) with a 60 s deadline. `x-test-2` is a provider test slot whose
backing model can change; task 4.2 re-checks it before live use.
