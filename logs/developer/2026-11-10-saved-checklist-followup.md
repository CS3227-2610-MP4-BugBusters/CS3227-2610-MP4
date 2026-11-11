# Live saved-checklist follow-up — 10 November 2026

## Summary

Two synthetic cases were sent directly to the configured SoC provider with one saved checklist step. Both responses were valid JSON lists and returned HTTP 200. Both included a loop-bound check similar to the supplied checklist step, so the instruction to avoid duplicates did not reliably work in this small sample. Human rubric scoring remains open.

## Setup and observations

- Starting revision `5443afe`. Fixtures `AI-C01` and `AI-C04` from `workflow/evaluation/developer/ai-live-fixtures.json` version `2026-11-09-v1`; rubric `workflow/evaluation/developer-ai-checklist-rubric.md`; prompt `workflow/evaluation/developer/ai-prompt-candidate-v4.txt`.
- Both synthetic requests included the saved step “Inspect whether the loop excludes the last record.” Model `qwen3.5:9b`, temperature 0, output cap 384 tokens, `reasoning_effort: none`, 15-second client deadline. No real report or credentials recorded.
- AI-C01: HTTP 200, 2.61 s, 230 prompt and 92 completion tokens; four steps. The range check overlapped with the saved step. Other steps suggested early returns, file handling and error paths; these are checks, not established causes.
- AI-C04: HTTP 200, 2.27 s, 231 prompt and 66 completion tokens; three steps. The loop-bound check overlapped with the saved step. The output did not repeat the fixture's request to close the report, but the direct request did not test application mutation or establish injection resistance.

## Limits and reflection

These are direct provider calls, not an authenticated app or browser test. Only one response per case was observed. The calls do not verify output recovery, authorization, persistence, stale-draft handling, or no domain mutation. Provider-reported token counts are available; cached/reasoning tokens, quota and cost are unavailable. The separate automated authenticated integration and security evidence is recorded in `2026-11-10-ai-missing-comma-recovery.md` and `2026-11-10-saved-checklist-ai-context.md`.
