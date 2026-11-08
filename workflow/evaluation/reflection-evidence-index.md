# MP4 reflection and presentation evidence

## Available now: development process

| Case | What happened | What it can support | Limits |
| --- | --- | --- | --- |
| Shared report storage | An independent security reviewer caught a race test that could pass without actual overlap; the test designer corrected it and two reviewers reran it. | A concrete coordination handoff, review finding and quality improvement. [Review log](../../logs/shared/2026-11-08-shared-report-storage-review.md) | Local PostgreSQL/raw SQL and synthetic UUIDs. No per-agent token or cost figures. |
| Status filter | Scoped agents designed tests, verified Red, implemented, and reviewed correctness/security. | Context given to roles, coordination and model/effort decisions. [Context and resource record](../../logs/developer/2026-11-08-status-filter-context-resource.md) | No measured token, total time or cost comparison; synthetic app integration. |
| Assigned-report search | Security review found malformed encoding and Unicode matching problems; changes were independently rechecked. | Example of findings and rework caused by a deeper review. [Search evidence](../../logs/developer/2026-11-08-assigned-report-search.md) | No measured savings; local synthetic sessions. |
| Context comparison | Two same-model agents reviewed the schema with broad-discovery versus targeted starting context; each surfaced useful gaps. | A small exploratory example of different context entry points. [Run record](../../logs/evaluation/2026-11-08-context-comparison.md) | Not controlled: no pre-frozen rubric or isolated snapshot, no tokens/timing. Cannot conclude one approach is more efficient. |

## Still to collect: app AI

Use the [Developer AI evidence plan](developer-ai-evidence-plan.md) once the real feature and gateway exist. Collect a synthetic ordinary-use case, context exclusion, authorization, injection, stale-result and timeout/limit cases, plus provider-reported usage if exposed. No app-side AI run is claimed here.

## Presentation structure

1. **Problem and workflow:** Show one feature, task roles and handoff sequence.
2. **Concrete review finding:** Explain the race-test weakness, correction and rerun, with evidence link.
3. **Context and resources:** Show context supplied and model/effort choices. Explicitly label unavailable token/cost measurements and report the exploratory comparison's limitations.
4. **App-side evidence:** Show actual input selection, safety/limit tests and usage only after they are implemented and measured.
5. **Reflection:** Explain when coordination helped, what caused retries or extra work, and the next improvement. Avoid unsupported claims about savings or full production readiness.
