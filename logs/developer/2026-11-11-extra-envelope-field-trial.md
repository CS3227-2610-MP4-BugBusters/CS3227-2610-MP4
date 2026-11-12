# Developer suggestion envelope trial — 11 November 2026

## Summary

The Developer adapter now accepts a JSON object containing a valid `steps` array alongside unrelated fields, discards those fields, and retains full-response credential scanning and strict JSON/output validation. This addresses one observed Qwen response shape. One subsequent authenticated synthetic live request still returned HTTP 502 `AI_INVALID_OUTPUT` in 7.54 seconds; report status and version remained unchanged. The live issue is not resolved.

## Coordination and checks

- Baseline revision `0b35be5`. Test designer wrote an accepting case and malformed/duplicate/trailing/oversized/credential controls. Independent baseline verification observed eight tests, one expected failure for the extra field. Approved test SHA `e05c6e924bb645c084f40e20205463e226e437f25f8847e0216f367c4a50a1c1`.
- Implementer removed the one-property restriction only. Candidate verifier independently confirmed the approved baseline and focused test passed. Independent security review found no actionable bypass; it requested an escaped credential in an ignored field test. The added test was independently rerun: nine tests passed. Current test SHA `9b47b9d30ae4ff37591ebfe858e406159f10b8b8a915f54bc48df58171c8385a`.
- An attempted full auth suite was run without the disposable database environment; 45 database-dependent tests failed for missing setup. It was not valid integration evidence. One additional test was initially edited in the wrong workflow worktree, then removed and added to the intended main checkout before independent verification.
- Live follow-up used a temporary isolated database and synthetic accounts. No provider raw output, actual provider token usage or exact rejecting validation layer was captured. No conclusion about reliability or prompt-injection resistance follows from one live attempt.

## Limits

The prompt still requests one `steps` property as the preferred format; accepting other fields is a tolerance rule. Empty content, invalid JSON, duplicate keys, trailing data, credential reflections, bounds and authorization remain protected. Further provider diagnostics and gateway-owner coordination are needed; no OpenSpec task is marked complete. Agent token totals were unavailable.
