# Design

## Context

Migrated the technical decisions from MNT-SPEC-1 into the planning change described
in [proposal.md](proposal.md). Implementation now includes authenticated Maintainer workflows, shared PostgreSQL
adapters and the queue-summary endpoint; see PR #10 for checks and pending reviews.
The current query DTOs are recorded in the [implemented baseline](../../specs/maintainer-report-browser/spec.md).
Numeric application policies below are application bounds, not verified SoC limits.
Shared-owner review and live provider/deployment checks remain pending.

## Goals / Non-Goals

Preserve role requirements, proposed service seams, security controls and original
verification IDs without duplicating shared stores or lifecycle logic. Keep role
prompts/context separate from shared provider mechanics. Permit isolated fixtures
while real adapters are incomplete; require real service checks before integration.
The migration originally authorized no application edits; subsequent implementation
was authorized separately and is tracked in PR #10.

Use the active ownership table in section 2 (page 2) of the original plan, not the
crossed-out workload split on pages 17-19. Own authentication/provisioning and
Render/GitHub Actions/environment configuration. The Reporter owner reviews auth;
the Developer owner reviews deployment. The Developer owner owns shared database
schema/migrations/version checks and shared frontend/API handling; review and
coordinate compatibility. The Reporter owner coordinates documentation/site/evidence
and evaluation; contribute and review without assuming final document assembly.
Prioritize [shared authentication](../add-shared-authentication/design.md) and
trusted-actor integration before another fake-backed role UI increment.
Reporter-owned source, Developer-owned investigation/checklists and the shared AI
provider retain their implementation boundaries. Use React, Java and PostgreSQL
per the plan; keep controllers free of SQL and lifecycle rules.

## Decisions

### Structure and shared seams

I propose a React Maintainer feature and Java Maintainer controllers/DTOs, query service, triage service and summary service under src/. Keep controllers free of SQL/lifecycle rules and depend on shared authenticated actor, report query/lifecycle, discussion and AI gateway interfaces. Use PostgreSQL adapters and shared migrations rather than a second report store. Define physical paths and executable checks after the scaffold exists.

Require trusted actor identity/current session from authentication, not request bodies. Require an active MAINTAINER for every endpoint, rechecked at writes and AI delivery. Propose cookie-session CSRF protection for unsafe methods, UUID identifiers, ISO-8601 UTC timestamps, JSON camelCase and Cache-Control: no-store. Reject unknown body/query fields, invalid enums/IDs and request bodies over 64 KiB. Expose no credentials, session identifiers or provider secrets in DTOs/logs.

### Legacy shared-schema compatibility

Validate required defaults as well as types, nullability and constraints before
baselining manually created report/checklist tables. Require canonical Submitted/
zero report defaults and empty-array/zero checklist defaults. Reject incompatible
schemas before recording shared history or applying additive migrations; do not
evaluate arbitrary default expressions or silently repair another owner's schema.
Keep the canonical Developer SQL and any uncommitted resolution migration unchanged.

### Proposed HTTP and DTO contracts

Prefix the following paths with /api/maintainer. Use exact bodies and reject caller-supplied actor/role/status overrides. Return a safe error {code,message,requestId}, without stack/provider traces. Propose 401 UNAUTHENTICATED, 403 FORBIDDEN, 404 NOT_FOUND, 400 VALIDATION_FAILED, 409 STALE_VERSION/INVALID_STATE and 503 TEMPORARILY_UNAVAILABLE for exhausted database waits; do not silently retry writes.

| Operation | Request | Response |
| --- | --- | --- |
| GET /reports | Accept optional status, priority, assigneeId and limit (default 50, maximum 100), offset (default 0). | Return {items:ReportSummary[],total} ordered updatedAt descending, ID ascending. |
| GET /reports/{id} | Accept no body. | Return ReportDetail with investigation, checklist, responses, current proposal and retained history. |
| GET /developers | Accept no filters. | Return active Developer {id,displayName} summaries ordered displayName then ID. |
| PUT /reports/{id}/priority | Accept {expectedVersion,priority}. | Return the updated ReportSummary. |
| POST /reports/{id}/assignment | Accept {expectedVersion,developerId,reason?}; require reason for reassignment. | Return the updated ReportSummary. |
| POST /reports/{id}/resolution/return | Accept {expectedVersion,proposalId,proposalVersion,reason}. | Return the updated ReportSummary. |
| POST /reports/{id}/close | Accept {expectedVersion,proposalId,proposalVersion,reviewed:true}. | Return the updated ReportSummary. |
| POST /reports/{id}/reopen | Accept {expectedVersion,reason}. | Return the updated ReportSummary. |
| POST /reports/{id}/comments | Accept {expectedVersion,text}. | Return 201 {comment,reportVersion}. |
| POST /queue-summary | Accept {filters:{status?,priority?,assigneeId?},regenerate:boolean}. | Return SummaryResult; intersect filters with status != Closed and reject status Closed. |

Define ReportSummary={id,title,status,priority,reporter:{id,displayName},assignee:null-or-{id,displayName},version,createdAt,updatedAt}. Propose ReportDetail to extend it with description,reproductionSteps,expectedBehavior,actualBehavior,environment,notes,checklist,responses,comments,currentProposal,history using shared DTOs agreed before implementation. Never allow the Maintainer DTO to imply edit ownership of these source fields.

Require expectedVersion/proposalVersion to be nonnegative integers. Trim reason/comment text, reject blank/control characters except newline/tab, and bound reasons to 1-1000 Unicode code points and comments to 1-4000. Render stored content as escaped text. Preserve user input on rejection and refresh the eligible-action state after success.

### Version and transaction contract

Propose one report aggregate version increment for each published source, discussion, priority, assignment, proposal or lifecycle mutation. Follow CL-05 for checklists: private Save increments only draft_revision; unchanged Share changes neither proposal nor report version; changed Share increments report version and invalidates a pending proposal atomically. Share checks both expected draft revision and report version. Reject empty Share unchanged. Require report-affecting child-edit methods to lock the report first and update this version, except private checklist Save and private Developer notes save/clear under their separate revision contracts; independent child counters can exist but cannot replace aggregate invalidation. Require a consistent lock order of current actor/account eligibility, selected Developer eligibility when applicable (accounts sorted by ID), then report, then children. Require all roles and deactivation operations to agree on the order before implementation.

Execute actor validation, target Developer eligibility, expected-version comparison, lifecycle checks, data update, proposal supersession/clearing, aggregate increment and history append in one database transaction. Commit no partial history or assignment. Compare both current proposal ID/version and report version for closure; reviewed:true records explicit UI acknowledgement, not evidence that a human understood the proposal. Preserve superseded proposals rather than deleting them.

Require Reporter/Developer edits that invalidate proposals to use the same report lock and transaction. Private Developer notes are never part of the Maintainer detail or AI summary context; saving or clearing them preserves the proposal, report status and aggregate version. Test closure against concurrent shared detail/response/note edits and changed published checklist Share in both commit orders: An invalidating edit that commits first makes closure stale; closure that commits first makes unresolved-only edits ineligible. Test reassignment against old-assignee reads/writes/AI delivery using fresh server authorization; cannot retract already delivered browser data. Propose a 5-second lock wait and safe retry-after-refresh behavior, subject to the shared database contract.

### Summary snapshot and context selection

Define the selected queue as every accessible report matching canonical filters and status != Closed, independent of the list's current page. Propose a short REPEATABLE READ snapshot transaction that reads the full ordered queue identity/version set and selected input fields. Bound snapshot enumeration to 1000 members; beyond it return 422 QUEUE_TOO_LARGE and request narrower filters with zero provider calls. Retain the full member {id,version} vector and current assignment per member for rechecks, including members excluded by context limits.

Order selection by priority CRITICAL,HIGH,MEDIUM,LOW, then oldest updatedAt, then ID. Include at most 20 reports and 24 KiB of UTF-8 serialized context data, stopping before a whole report exceeds the remaining budget. Propose field caps of title 200, description 1000, reproductionSteps 1000, expectedBehavior 500, actualBehavior 500 and environment 300 code points, plus the latest three history/comment updates each capped at 300. Include status, priority, ID, source version and bounded update times. Mark field truncation explicitly. Exclude identities/emails, credentials, raw full discussions, investigation/checklist/response bodies and hidden operational data from this initial summary payload. Compute included/excluded IDs and reasons on the server, never from model claims.

End the snapshot transaction before any provider call. Reauthorize the session/current active role and take a fresh consistent snapshot before delivery or reuse. Compare canonical filter membership, every member version and assignment, prompt/context policy and model identity. Reject changed membership or versions with 409 AI_STALE_SOURCE and no generated text; reject lost access without generated content. Do not hold database locks across provider latency or claim sources cannot change immediately after the final snapshot.

### Prompt and output contract

Version the prompt MNT-SUMMARY-1. Give trusted instructions separately from serialized report data, state that all report text is untrusted evidence, forbid following embedded instructions, request supplied IDs for each claim and require uncertainty where evidence is absent. Grant no model tools, retrieval, credentials or actions. Instruct the model not to claim investigations/tests occurred without source support. Do not rely on delimiters or prompting alone for authorization or safe rendering.

Require exact JSON {overview:[Finding],blockers:[Finding],missingInformation:[Finding]}, with Finding={text,reportIds}. Allow 0-8 findings per array, text 1-600 code points and 1-20 unique report IDs per finding. Reject extra keys, wrong types, duplicate/unknown IDs, blank text, output over 32 KiB and total finding text over 8000 code points. Accept IDs only from included inputs. Render all generated text as plain text without HTML/Markdown execution or generated links; construct report links from validated IDs. Evaluate factual grounding separately because structural validation does not prove truth.

Return SummaryResult={capturedAt,filters,included:[{id,version,truncatedFields}],excluded:[{id,reason}],findings,requiresReview:true,providerMode,promptVersion}. Use an empty deterministic result/providerMode none for an empty queue. Make summary generation read-only: No assignment, priority, proposal, status or investigation mutations can originate from output.

### Resource and reuse proposals

Propose one in-flight summary/account, three generation starts per rolling five minutes/account, a 30-second end-to-end provider deadline, output-token cap 2048, no automatic retry/repair call and no unbounded worker queue. Require the shared gateway to enforce stricter global durable call/token budgets across roles, replicas and environments. Retain in-flight protection until the worker actually terminates, even after a client timeout, with at least a 60-second lease renewed while work remains; require shared infrastructure review for these semantics. Fail closed when production quota accounting is unavailable.

Label all numeric values above as application proposals, not verified SoC provider limits. Require the gateway owner to confirm real model/API/token accounting/quota/host connectivity before activating live generation. Allow labelled deterministic fakes only in development/test. Return 409 AI_BUSY, 429 AI_RATE_LIMITED with Retry-After, 502 AI_INVALID_OUTPUT, 503 AI_UNAVAILABLE or 504 AI_TIMEOUT without raw provider data.

Propose a transient per-account cache of one valid result for at most five minutes. Key it by actor/session generation, canonical filters, full membership/version/assignment vector, selected payload digest, prompt/context policy and model configuration. Perform the same fresh authorization/snapshot recheck on every reuse and expire on any mismatch. Allow explicit regenerate subject to budgets. Do not share results across accounts or persist raw prompts in public evidence. Record sanitized timing/call/error/available token counts without inventing unavailable reasoning/cache metrics.

### Verification and open integration contracts

Derive Q1/T1/A1/R1/D1/S1/X1/S2 tests from the product criteria and [verification matrix](verification.md), using deterministic provider fixtures, API authorization tests, browser checks and real PostgreSQL races. Reserve live SoC trials for separately budgeted usefulness/injection evaluation. Record actual commands and candidate revisions only when executed.

Require owner agreement on remaining shared DTOs/errors, auth/session generation/CSRF, account eligibility lock coordination, discussion rules, queue snapshot adapters, durable budgets/cancellation and SoC activation before integration. Preserve the confirmed checklist report-first lock order relative to checklist/proposal rows, shared-only projections and separate draft revision; do not reopen CL-04/CL-05 as unresolved proposals. Can specify fake-backed adapters while these seams remain open; claim no backend, frontend, test, deployment or approval from this draft.

### Review and UI policy

Carry forward LOW, MEDIUM, HIGH and CRITICAL priorities with proposed MEDIUM default.
The existing local filter vocabulary is not proof of shared enum agreement. Keep
Closed-report comments proposed until the discussion owner confirms the policy.
Priority and ordinary discussion changes increment the aggregate version but do
not themselves supersede a proposal; clarification requests leave status unchanged.
Explicit review acknowledgement is required for closure, not an AI approval.

Use a queue with filters and summary panel, and report details with priority,
assignment, discussion and eligible resolution actions. Keep empty/loading/failure
states distinct, disable duplicate actions, preserve reasons/comments on recoverable
errors, and discard late responses after access/selection changes. Keep non-AI
workflows usable on provider failure. Source and generated content render as text.

The proposed report-first protocol relative to checklist/proposal rows follows
[the confirmed checklist contract](../../../workflow/developer/checklist-storage-contract.md).
All account eligibility locks must be coordinated before the report lock, with
accounts ordered by ID; do not introduce a reverse lock dependency.

### Context, agent resources and reflections

Use the current roles and sequence in [the TDD runbook](../../../workflow/tdd.md),
including a fresh independent Red verifier and feature-owner approval before Green.
The old generator-verifier plan does not waive these newer gates. Supply task IDs,
spec revision, allowed paths/tools, fixtures, verification command, model/effort,
time/token budgets, concurrency and repair limit before dispatch. Keep one writer
per file; isolate independent writers in separate worktrees. Pass concise handoffs
with decisions, failed approaches, checks, candidate references and next action;
retrieve supporting context as needed. Stop on budget exhaustion and resolve scope
disagreements with the owner. Propose at most two repair rounds per bounded task.

Freeze fixtures, model/prompt settings, scoring rubric and run budgets before
reflection comparisons. Contribute two legitimate Maintainer examples to the team's
context experiment: full permitted context versus selected bounded fields using
the same model/prompt. Use the agreed grounding/completeness/usefulness rubric,
record unsupported claims and safety failures, and separate availability failures.
Follow the original plan section 12 protocol: the Reporter owner coordinates six
synthetic examples (two per role), three runs per condition per example within the
confirmed quota, and human scores of 0-2 each for grounding, completeness and
usefulness (total 0-6). Contribute the two Maintainer examples in both conditions.
Freeze this protocol before runs; if the quota cannot support it, record the
constraint and an explicit revised protocol rather than silently reduce repeats.

Compare valid unchanged-result reuse with regeneration, then independently change
report content, assignment, membership, permissions, prompt/context policy and model.
Include checklist/response versions only if actually consumed by the feature.
Measure provider calls, latency, invalidation correctness and exposed token metrics.
Contribute actual handoff/defect/retry evidence to coordination analysis. Claim a
controlled single-agent comparison only after isolated comparable runs. Compare
provider prompt caching only if cached-input measurements are exposed; keep a stable
instruction prefix versus changing-data-first condition with equal task/model/output
budget. Report unavailable cache/reasoning-token measurements rather than guessing.

## Risks / Trade-offs

- Draft-only data in report detail -> use the same shared-only checklist projection
  for direct and embedded responses; future tests must inspect absence, not only UI.
- Long-lived AI work -> release DB transactions before calls and recheck all queue
  members and access before return/reuse; source may still change after that snapshot.
- Fakes mistaken for integration -> separate deterministic results from authenticated
  API/PostgreSQL/live SoC/browser release evidence.
- Shared quota or cancellation uncertainty -> fail closed in production and preserve
  worker leases until actual termination; align replicas/environments with gateway owner.

## Migration Plan

The original migration moved documentation only. Subsequent implementation is
tracked in PR #10. Retain historical commits and logs; remove the
five superseded Maintainer workflow documents after independent coverage review.
Use [verification.md](verification.md) and the new delta scenarios as test oracles.
Future implementation follows tasks.md and the approved Red baseline, never this
migration's file-existence status. Coordinate additive migrations with data owners;
use disposable databases and synthetic identities for isolated tests.

For release, use one agreed build/test/lint/security verification command in CI,
team-managed Render deployment, distinct development/production databases/users/
credentials/configuration and environment-scoped AI secrets. If SoC credentials must
be shared, record the provider constraint and coordinate quotas without secret values.
Verify actual approval/protection settings and the deployed commit rather than infer
protection from an environment name. Reconcile main versus the plan's master submission
branch before release. Keep GitHub Pages separate from app hosting. Verify login/read
and wrong-role denial in each environment plus the cross-role browser flow; record
sanitized evidence. No deployment is claimed by this migration.

Update UserGuide, DeveloperGuide (including acknowledgements and workflow file
explanations), and Reflections contributions to match actual release behavior.
Keep verified sanitized interaction summaries under logs/. Shared assembly remains
with its assigned owner; do not claim team acceptance from an agent PASS.
