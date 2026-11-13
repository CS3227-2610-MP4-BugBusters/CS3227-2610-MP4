# Shared notes and sidebar: recovered Codex token observations

## Summary

A later inspection of locally persisted Codex session metadata found token counters for the coordinator and identifiable specialist agents used in the shared-notes change, and for the coordinator's later sidebar-link change. Earlier logs said these figures were unavailable to the coordinator during execution; that described what was exposed at the time, but was incomplete because the local records were not inspected. This record corrects that limitation. It contains counters and identifiers only, not prompts, tool output, credentials or report data.

These are observational measurements of two **different tasks**, not a controlled comparison of one workflow against another. Input counts include cached input and repeated context sent on successive model calls. Cached input is a subset of input; reasoning output is a subset of output. Do not add those subsets again, equate reported total with uncached/billed tokens, infer cost, or interpret the context's repeated inputs as unique words in the project.

## Method and scope

Read only metadata from locally stored Codex JSONL records under `~/.codex/sessions/` for chat `01a1cf75-a705-7bd2-9834-d4bfa72bc87a`. The shared-notes request was root turn `01a1d0d5-d083-7751-9e73-e3c2cdf9061c` (starting `2026-11-12T17:37:15Z`); the sidebar request was turn `01a1d26e-bb60-7623-989c-2dc62af5915a` (starting `2026-11-13T01:03:54Z`). UTC timestamps come from those records. For each `thread_id` and `turn_id`, take the final `token_usage_record.payload.turn_token_usage` snapshot once; do not sum cumulative snapshots or repeat the same `last_token_usage` when a resumed turn emits a duplicate. Associate specialist session files with named dispatches using the recorded spawn timestamp and `root_turn_id`; sum the test designer's seven and security reviewer's three distinct turns once each. Settings come from each turn's `turn_context`, not from assumed defaults.

The shared-notes coordinator total covers its root turn, including interruptions and follow-up work within that turn; specialist totals are from their separate thread IDs. The listed subtotal excludes guardian/automatic-approval-review sessions and any unidentifiable or missing records. It is not a billing figure, an account-wide total, or proof of complete tooling visibility. No raw traces were copied into the repository. The sidebar turn had no delegated specialists.

## Coordinator measurements

| Task | Model / effort | Input | Cached input (subset) | Output | Reasoning output (subset) | Reported total |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Shared-note feature and verification | gpt-6.1-terra / medium | 77,215,608 | 76,809,216 | 29,086 | 7,261 | 77,244,694 |
| Remove Developer Account link | gpt-6.1-terra / medium | 5,002,056 | 4,992,768 | 2,867 | 487 | 5,004,923 |

For these coordinator records, cached input was 99.47% and 99.81% of reported input respectively; reasoning output was 24.96% and 16.99% of reported output. These ratios describe recorded token categories, not the reduction relative to an uncached control, response quality, latency or cost. The shared-note task involved database migration, authorization, lifecycle/race tests, reviews and multiple handoffs; the sidebar task was a small frontend change. Their raw totals are not an apples-to-apples estimate of multi-agent overhead.

## Shared-notes specialist measurements

All values are totals of final per-turn snapshots for the named specialist, not of every event. Multiple follow-up turns for the same specialist are counted separately once each.

| Specialist | Role / model / effort | Turns | Input | Cached input | Output | Reasoning output |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| architecture | explorer / gpt-6.1-terra / medium | 1 | 2,054,712 | 2,042,112 | 1,316 | 98 |
| shared_notes_red | test designer / gpt-6-luna / high | 7 | 28,077,350 | 27,157,760 | 83,125 | 30,451 |
| shared_notes_baseline | baseline verifier / gpt-6-luna / high | 1 | 1,034,574 | 720,896 | 4,333 | 1,305 |
| shared_notes_baseline_v2 | baseline verifier / gpt-6-luna / high | 1 | 980,054 | 679,936 | 4,486 | 1,276 |
| shared_notes_baseline_v3 | baseline verifier / gpt-6-luna / high | 1 | 1,222,458 | 1,121,024 | 5,177 | 2,137 |
| shared_notes_baseline_final | baseline verifier / gpt-6-luna / high | 1 | 1,333,024 | 1,240,832 | 5,881 | 2,627 |
| shared_notes_baseline_fixed | baseline verifier / gpt-6-luna / high | 1 | 1,135,077 | 827,520 | 4,319 | 1,240 |
| shared_notes_fixture_baseline | baseline verifier / gpt-6-luna / high | 1 | 1,469,776 | 1,120,896 | 5,277 | 719 |
| shared_notes_waiter_baseline | baseline verifier / gpt-6-luna / high | 1 | 1,693,249 | 1,328,896 | 5,332 | 1,652 |
| shared_notes_backend | implementer / gpt-6-luna / high | 1 | 9,433,819 | 8,802,944 | 31,381 | 11,289 |
| shared_notes_frontend | worker / gpt-6.1-terra / medium | 1 | 6,108,801 | 6,090,112 | 9,450 | 1,786 |
| shared_notes_security | security reviewer / gpt-6.1-sol / medium | 3 | 6,046,085 | 5,666,432 | 4,069 | 1,009 |
| shared_notes_candidate | candidate verifier / gpt-6-luna / low | 1 | 1,374,575 | 1,009,792 | 2,742 | 182 |
| **Listed specialists** | 13 identifiable agents | 21 | **61,963,554** | **57,809,152** | **166,888** | **55,771** |

The listed specialist subtotal is **62,130,442** reported total tokens; adding it to the coordinator's separate-thread total gives **139,375,136** for these selected records. This sum includes cached/repeated input and excludes guardian sessions, so it is not a complete billable usage total. Seven baseline verifiers and seven test-designer turns show that revisions and repeated handoffs consumed resources, but the logs do not isolate how much of the total was caused by any individual mistake or what a single-agent alternative would have used.

## Separate CLI review in locally recorded main

The locally recorded `origin/main` at `03b415b` contains `logs/developer/2026-11-13-role-visual-alignment.md`, not present in the current checkout. Its completed `codex exec --json` review event reports **146,300 input**, including **112,000 cached input**, and **975 output**, including **96 reasoning output**. The review found three UI issues; the implementation was adjusted, but that reviewer was not rerun. These figures cover that one CLI review only, exclude the rest of its task and earlier failed attempt, and are separate from the local-session measurements above. Read it with `git show origin/main:logs/developer/2026-11-13-role-visual-alignment.md`. The raw trace is recorded as a local temporary artifact, not committed evidence; do not copy it without sanitization.

## Verification and limitations

Matched each specialist session's first `task_started` timestamp to a recorded `spawn_agent` call within three seconds and filtered usage by the shared root turn. The corrected final baseline and candidate tests, reviewer findings, setup restrictions and actual test outcomes are documented in [shared notes workflow evaluation](../developer/2026-11-13-shared-notes-workflow-evaluation.md) and [Red evidence](../developer/2026-11-13-shared-notes-red.md). The sidebar work and checks are in [the sidebar log](../developer/2026-11-13-remove-developer-account-link.md).

These records do not independently establish per-agent monetary cost, provider billing, network latency, energy use or the token count of an alternative workflow. The logged wall-clock interval for shared notes includes user interruptions and waiting; it is not active agent time. The counters may omit runtime activity not represented by the identified records. Manual role matching and aggregation are an analysis of local records, not a validated billing export.
