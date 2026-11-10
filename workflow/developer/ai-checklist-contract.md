# Developer AI checklist draft: local integration contract

This contract is for the first Developer-owned slice. The Reporter-owned
shared AI gateway, confirmed SoC quota and deployment policy remain release
dependencies; the limits below are application proposals, not provider quota.

## Request and response

`POST /api/reports/{reportId}/checklist/suggestions` requires a real active
Developer session and CSRF. The JSON body is exactly `{}`. The server obtains
the actor and report from trusted storage; client-supplied report text or
identity is rejected. An authorized successful response is
`{ "steps": ["..."], "sourceVersion": 7 }` with `Cache-Control: no-store`.
Steps are advisory plain text: 1–8 nonblank strings, each at most 300 UTF-8
bytes, no NUL, valid Unicode. Invalid provider output is rejected, not
partially applied or returned raw.
Each suggestion is one line: reject CR and LF so the reviewed list cannot
silently turn one suggested step into multiple saved checklist steps.

Only the selected report's title and description, each limited to 512 and
8192 UTF-8 bytes respectively, are sent as untrusted data. If either exceeds
its cap, reject rather than silently truncating. Do not include unrelated
reports, private notes, credentials, account data or raw request headers.
No model output or report text goes into application logs.

## Security and lifecycle

Before the call, verify current active account, session generation and
assignment, and snapshot report version, title and description. A request
that cannot be authorized invokes no provider. Before delivering the result,
perform a fresh persisted-session lookup (including expiry and identity),
and recheck account generation, role, assignment, source version and source
fields. Reject late results when source or access changed; never return steps.
Do not rely on the request's cached session object to detect logout.
Capture the server-side session ID, use the Spring Session repository to
reload it without saving the result, enforce absolute session expiry, and
verify the freshly stored security principal matches the original actor and
generation. Repository failure denies delivery. The final check establishes
freshness at that check; it cannot prevent a change after the check completes.
Account eligibility locks precede report locks when both are needed. Do not
hold either lock during an external request. Generation cannot save, share,
close, change status or use tools. The UI displays suggestions for review
and requires explicit Apply, then a separate Save and Share. Before Apply,
recheck the current report version through the authenticated API and discard
the preview if it differs; isolate pending requests by report and session.

## Provider and limits

The database snapshot must reject an oversized title or description before
loading either full value into application memory. Perform byte-length checks
and return only bounded values while preserving the same report lock,
authorization and version snapshot. Oversized input must not reach the provider.

The Developer provider uses the shared SoC role gateway, configured with
`SOC_LLM_ENABLED`, `SOC_LLM_MODEL`, `SOC_LLM_RPM`, `SOC_LLM_TPM` and
`SOC_LLM_API_KEY_DEVELOPER`. It passes the authenticated Developer UUID and
`DEVELOPER_ASSIST` task from trusted backend code. Credentials are never sent to
the browser or logged; legacy `SOCLAAS_*` settings do not activate a provider.
Use a finite 15-second feature deadline and 384 output tokens. The shared
gateway owns transport, per-key admission and quota enforcement. Preflight the
serialized report/checklist context against the gateway's 24,576-byte limit and
return `INPUT_TOO_LARGE` before admission when it exceeds that bound. Do not
claim provider quota or production availability without measured evidence.

The fixed Developer system instruction is the reviewed candidate in
`workflow/evaluation/developer/ai-prompt-candidate-v4.txt`; preserve its
untrusted-input boundary and behavior while using the shared gateway's required
JSON-object response envelope.

Use safe structured errors `{code,message,requestId}` and no-store. Denied
access returns 403 for an authenticated but unauthorized actor; missing or
invalid session returns 401. Stale input returns 409 `STALE_SOURCE`; oversized report context
400 `INPUT_TOO_LARGE`; malformed provider output 502 `AI_INVALID_OUTPUT`;
provider unavailable 503 `AI_UNAVAILABLE`; timeout 504 `AI_TIMEOUT`; local
capacity 429 `AI_BUSY`. No provider error body or prompt is returned or logged.
Record sanitized outcome, model, duration and provider token counts if
available; explicitly mark missing metrics unavailable. No automatic retries.

## Evaluation

Freeze synthetic legitimate, unauthorized, injection, invalid-output, timeout
and late-result cases before evaluation. Human review scores each legitimate
result 0–2 for relevance, actionability and grounding (0 absent/unsupported,
1 partial, 2 clear and supported); record invented facts separately. A timeout
or provider failure is unavailable evidence, not a safety success. A successful
connectivity call is not a usefulness or injection evaluation. Record harmful
suggested actions separately; structured JSON alone does not establish safety.
