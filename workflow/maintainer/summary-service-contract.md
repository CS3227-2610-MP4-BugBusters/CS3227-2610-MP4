# Maintainer queue-summary service boundary

Status: compileable setup boundary only. This document records the next behavior-task
contract; it does not claim summary behavior, authorization integration, persistence,
provider connectivity, or quota enforcement is implemented.

## Immutable Java API

`mp4.maintainer.summary.SummaryContracts` defines the trusted `Actor` (account UUID,
fixed role, active state, auth generation and opaque internal session identity),
canonical filters (the shared `MaintainerContracts.Status` and `Priority`, plus
assignee UUID), safe `ReportInput`, full `Snapshot`,
server-computed inclusion/exclusion metadata, validated finding/result shapes and
the ports used by `QueueSummaryService`. The actor's `toString()` redacts its session
identity. `ReportInput` has no field for private Developer notes, identities, emails,
credentials, checklists, response bodies or raw discussion. `Snapshot.reports` is the
entire selected queue with each source version and assignment, including items the
context selector may later exclude.

The gateway port receives distinct `TrustedInstructions` and `UntrustedReportsJson`
values and reports its model identity and provider mode. The budget port represents
durable shared admission and an in-flight lease that remains held until the provider
worker really ends. The cache port is per-account/session and can only be used after
fresh access and complete-snapshot checks. Implementations must keep real provider
calls outside database transactions.

`QueueSummaryService` has constructor-injected auth, full-snapshot, gateway, budget,
cache, limits, `Clock` and an `allowDeterministic` runtime-policy input. The shorter
constructor defaults `allowDeterministic` to false; deterministic gateways are
available only when a development/test caller explicitly opts in. Production wiring
must use the default-deny policy. Its `generate(actor, filters, regenerate)` method
currently throws deterministic `NOT_IMPLEMENTED` before consulting any port. The
setup test verifies that boundary and all imports/types compile; no generation
behavior has passed Red or Green.

`SummaryContracts.SummaryFailure` is the typed service/port failure seam. It maps
`UNAUTHENTICATED`→401, `FORBIDDEN`→403, `VALIDATION_FAILED`→400,
`QUEUE_TOO_LARGE`→422, `AI_STALE_SOURCE`/`AI_BUSY`→409,
`AI_RATE_LIMITED`→429, `AI_INVALID_OUTPUT`→502, `AI_UNAVAILABLE`→503,
`AI_TIMEOUT`→504 and `NOT_IMPLEMENTED`→503. Its only response messages are fixed
safe strings; it has no raw-message constructor. Optional Retry-After seconds are
positive integers capped at 3,600. Snapshot, gateway and budget adapters can throw
this typed runtime failure for known cases; unknown adapter errors will later map
to safe `AI_UNAVAILABLE` at the service boundary. The current
`QueueSummaryService.NotImplementedException` extends this failure type.

## Proposed application defaults

These values come from `complete-maintainer-role/design.md`; they are application
proposals and are not confirmed SoC provider quotas or tested production limits:

| Limit | Proposed value |
| --- | ---: |
| Full queue membership | 1,000 reports |
| Context selection | 20 reports / 24 KiB serialized UTF-8 |
| Validated response | 32 KiB |
| End-to-end provider deadline | 30 seconds |
| Output tokens | 2,048 |
| Per-account starts | 3 per rolling 5 minutes |
| Per-account in-flight work | 1; retain lease until actual worker termination, minimum 60-second renewable lease |
| Matching-result retention | 5 minutes |

An empty selected queue will eventually return a deterministic no-provider result.
The full queue snapshot will be checked against the 1,000-member cap before provider
admission. Context selection follows priority CRITICAL, HIGH, MEDIUM, LOW, then oldest
update, then UUID; it stops before exceeding either context cap or splitting a
report. Every omission and truncation is computed by the server. Regeneration remains
subject to budgets. Production must fail closed when durable shared quota accounting
is unavailable. Only explicitly labelled deterministic test/development gateways
may be fakes.

The shared status contract maps storage tokens to wire labels explicitly: `SUBMITTED`
→ `Submitted`, `ASSIGNED` → `Assigned`, `IN_PROGRESS` → `In progress`,
`RESOLUTION_PROPOSED` → `Resolution proposed`, and `CLOSED` → `Closed`. Summary
filters use those canonical enum values; an HTTP mapper later serializes wire labels.
Provider mode is an enum internally and should serialize as the API's lowercase
`none`, `deterministic` or `live` value.

The result shape is `capturedAt`, canonical filters, included `{id,version,
truncatedFields}`, excluded `{id,reason}`, `findings` (`overview`, `blockers`,
`missingInformation`), `requiresReview`, `providerMode` and `promptVersion`. The
intended prompt version is `MNT-SUMMARY-1`. Output validation must enforce the exact
JSON keys, bounds, unique report references drawn only from included inputs and
plain-text rendering. A structurally valid response is not proof of factual grounding.

## Integration seams still open

- **Authentication:** the shared auth principal currently carries account ID,
  display name, role and auth generation. The session-freshness filter checks the
  account's active state and generation, but this boundary has no Maintainer-specific
  `CurrentAuthorization` adapter or agreed current-session identity projection.
  Integrate trusted request/session identity without accepting actor fields from a
  request body; recheck current role, active state, generation and session before
  delivery and reuse. Resolve session identity representation with the auth owner.
- **Queue snapshot/storage:** existing `reports` has ID, title, description, reporter,
  assignee, status, aggregate version and current proposal ID. It does not currently
  store priority, created/updated timestamps, expected/actual behavior, reproduction
  steps or environment. The current Developer checklist table stores private draft
  steps/revision and shared steps. Do not use checklist draft data as Maintainer
  summary input. The shared schema/query owner must agree additive source fields and
  a consistent full-membership/version/assignment snapshot adapter. This service
  boundary adds no SQL, migration or second report store.
- **Provider and quota:** the gateway owner must confirm model/API identity, actual
  token accounting, shared durable quota, worker cancellation/lease behavior and
  deployment-host connectivity before live activation. The boundary does not create
  provider calls or claim a production implementation.
- **Cache policy:** keys must cover account/session generation, canonical filters,
  the full membership/version/assignment vector, selected payload digest,
  prompt/context policy and model configuration. Recheck access and a complete fresh
  snapshot before every cached delivery; invalidate on any mismatch.

No controller, endpoint, SQL, lifecycle mutation, private-note read, provider
implementation or frontend behavior is part of this setup task.
