# My Maintainer technical specification

I record draft revision MNT-SPEC-1 on 7 November 2026 against [my product specification](PRODUCT-SPEC.md). I propose the contracts below for owner review; I do not describe existing APIs, schemas, provider capabilities, or completed implementation.

## My structure and shared seams

I propose a React Maintainer feature and Java Maintainer controllers/DTOs, query service, triage service and summary service under src/. I keep controllers free of SQL/lifecycle rules and depend on shared authenticated actor, report query/lifecycle, discussion and AI gateway interfaces. I use PostgreSQL adapters and shared migrations rather than a second report store. I define physical paths and executable checks after the scaffold exists.

I require trusted actor identity/current session from authentication, not request bodies. I require an active MAINTAINER for every endpoint, rechecked at writes and AI delivery. I propose cookie-session CSRF protection for unsafe methods, UUID identifiers, ISO-8601 UTC timestamps, JSON camelCase and Cache-Control: no-store. I reject unknown body/query fields, invalid enums/IDs and request bodies over 64 KiB. I expose no credentials, session identifiers or provider secrets in DTOs/logs.

## My proposed HTTP and DTO contracts

I prefix the following paths with /api/maintainer. I use exact bodies and reject caller-supplied actor/role/status overrides. I return a safe error {code,message,requestId}, without stack/provider traces. I propose 401 UNAUTHENTICATED, 403 FORBIDDEN, 404 NOT_FOUND, 400 VALIDATION_FAILED, 409 STALE_VERSION/INVALID_STATE and 503 TEMPORARILY_UNAVAILABLE for exhausted database waits; I do not silently retry writes.

| Operation | My request | My response |
| --- | --- | --- |
| GET /reports | I accept optional status, priority, assigneeId and limit (default 50, maximum 100), offset (default 0). | I return {items:ReportSummary[],total} ordered updatedAt descending, ID ascending. |
| GET /reports/{id} | I accept no body. | I return ReportDetail with investigation, checklist, responses, current proposal and retained history. |
| GET /developers | I accept no filters. | I return active Developer {id,displayName} summaries ordered displayName then ID. |
| PUT /reports/{id}/priority | I accept {expectedVersion,priority}. | I return the updated ReportSummary. |
| POST /reports/{id}/assignment | I accept {expectedVersion,developerId,reason?}; I require reason for reassignment. | I return the updated ReportSummary. |
| POST /reports/{id}/resolution/return | I accept {expectedVersion,proposalId,proposalVersion,reason}. | I return the updated ReportSummary. |
| POST /reports/{id}/close | I accept {expectedVersion,proposalId,proposalVersion,reviewed:true}. | I return the updated ReportSummary. |
| POST /reports/{id}/reopen | I accept {expectedVersion,reason}. | I return the updated ReportSummary. |
| POST /reports/{id}/comments | I accept {expectedVersion,text}. | I return 201 {comment,reportVersion}. |
| POST /queue-summary | I accept {filters:{status?,priority?,assigneeId?},regenerate:boolean}. | I return SummaryResult; I intersect filters with status != Closed and reject status Closed. |

I define ReportSummary={id,title,status,priority,reporter:{id,displayName},assignee:null-or-{id,displayName},version,createdAt,updatedAt}. I propose ReportDetail to extend it with description,reproductionSteps,expectedBehavior,actualBehavior,environment,notes,checklist,responses,comments,currentProposal,history using shared DTOs agreed before implementation. I never allow the Maintainer DTO to imply edit ownership of these source fields.

I require expectedVersion/proposalVersion to be nonnegative integers. I trim reason/comment text, reject blank/control characters except newline/tab, and bound reasons to 1–1000 Unicode code points and comments to 1–4000. I render stored content as escaped text. I preserve user input on rejection and refresh the eligible-action state after success.

## My version and transaction contract

I propose one report aggregate version increment for every source, discussion, priority, assignment, proposal or lifecycle mutation. I require shared child-edit methods to lock the report first and update this version; independent child counters can exist but cannot replace aggregate invalidation. I require a consistent lock order of current actor/account eligibility, selected Developer eligibility when applicable (accounts sorted by ID), then report, then children. I require all roles and deactivation operations to agree on the order before implementation.

I execute actor validation, target Developer eligibility, expected-version comparison, lifecycle checks, data update, proposal supersession/clearing, aggregate increment and history append in one database transaction. I commit no partial history or assignment. I compare both current proposal ID/version and report version for closure; reviewed:true records explicit UI acknowledgement, not evidence that a human understood the proposal. I preserve superseded proposals rather than deleting them.

I require Reporter/Developer edits that invalidate proposals to use the same report lock and transaction. I test closure against concurrent detail/response/checklist edits in both commit orders: an invalidating edit that commits first makes closure stale; closure that commits first makes unresolved-only edits ineligible. I test reassignment against old-assignee reads/writes/AI delivery using fresh server authorization; I cannot retract already delivered browser data. I propose a 5-second lock wait and safe retry-after-refresh behavior, subject to the shared database contract.

## My summary snapshot and context selection

I define the selected queue as every accessible report matching canonical filters and status != Closed, independent of the list's current page. I propose a short REPEATABLE READ snapshot transaction that reads the full ordered queue identity/version set and selected input fields. I bound snapshot enumeration to 1000 members; beyond it I return 422 QUEUE_TOO_LARGE and request narrower filters with zero provider calls. I retain the full member {id,version} vector and current assignment per member for rechecks, including members excluded by context limits.

I order selection by priority CRITICAL,HIGH,MEDIUM,LOW, then oldest updatedAt, then ID. I include at most 20 reports and 24 KiB of UTF-8 serialized context data, stopping before a whole report exceeds the remaining budget. I propose field caps of title 200, description 1000, reproductionSteps 1000, expectedBehavior 500, actualBehavior 500 and environment 300 code points, plus the latest three history/comment updates each capped at 300. I include status, priority, ID, source version and bounded update times. I mark field truncation explicitly. I exclude identities/emails, credentials, raw full discussions, investigation/checklist/response bodies and hidden operational data from this initial summary payload. I compute included/excluded IDs and reasons on the server, never from model claims.

I end the snapshot transaction before any provider call. I reauthorize the session/current active role and take a fresh consistent snapshot before delivery or reuse. I compare canonical filter membership, every member version and assignment, prompt/context policy and model identity. I reject changed membership or versions with 409 AI_STALE_SOURCE and no generated text; I reject lost access without generated content. I do not hold database locks across provider latency or claim sources cannot change immediately after the final snapshot.

## My prompt and output contract

I version the prompt MNT-SUMMARY-1. I give trusted instructions separately from serialized report data, state that all report text is untrusted evidence, forbid following embedded instructions, request supplied IDs for each claim and require uncertainty where evidence is absent. I grant no model tools, retrieval, credentials or actions. I instruct the model not to claim investigations/tests occurred without source support. I do not rely on delimiters or prompting alone for authorization or safe rendering.

I require exact JSON {overview:[Finding],blockers:[Finding],missingInformation:[Finding]}, with Finding={text,reportIds}. I allow 0–8 findings per array, text 1–600 code points and 1–20 unique report IDs per finding. I reject extra keys, wrong types, duplicate/unknown IDs, blank text, output over 32 KiB and total finding text over 8000 code points. I accept IDs only from included inputs. I render all generated text as plain text without HTML/Markdown execution or generated links; I construct report links from validated IDs. I evaluate factual grounding separately because structural validation does not prove truth.

I return SummaryResult={capturedAt,filters,included:[{id,version,truncatedFields}],excluded:[{id,reason}],findings,requiresReview:true,providerMode,promptVersion}. I use an empty deterministic result/providerMode none for an empty queue. I make summary generation read-only: no assignment, priority, proposal, status or investigation mutations can originate from output.

## My resource and reuse proposals

I propose one in-flight summary/account, three generation starts per rolling five minutes/account, a 30-second end-to-end provider deadline, output-token cap 2048, no automatic retry/repair call and no unbounded worker queue. I require the shared gateway to enforce stricter global durable call/token budgets across roles, replicas and environments. I retain in-flight protection until the worker actually terminates, even after a client timeout, with at least a 60-second lease renewed while work remains; I require shared infrastructure review for these semantics. I fail closed when production quota accounting is unavailable.

I label all numeric values above as application proposals, not verified SoC provider limits. I require the gateway owner to confirm real model/API/token accounting/quota/host connectivity before activating live generation. I allow labelled deterministic fakes only in development/test. I return 409 AI_BUSY, 429 AI_RATE_LIMITED with Retry-After, 502 AI_INVALID_OUTPUT, 503 AI_UNAVAILABLE or 504 AI_TIMEOUT without raw provider data.

I propose a transient per-account cache of one valid result for at most five minutes. I key it by actor/session generation, canonical filters, full membership/version/assignment vector, selected payload digest, prompt/context policy and model configuration. I perform the same fresh authorization/snapshot recheck on every reuse and expire on any mismatch. I allow explicit regenerate subject to budgets. I do not share results across accounts or persist raw prompts in public evidence. I record sanitized timing/call/error/available token counts without inventing unavailable reasoning/cache metrics.

## My verification and open integration contracts

I derive Q1/T1/A1/R1/D1/S1/X1/S2 tests from the product criteria and [test matrix](TEST-MATRIX.md), using deterministic provider fixtures, API authorization tests, browser checks and real PostgreSQL races. I reserve live SoC trials for separately budgeted usefulness/injection evaluation. I record actual commands and candidate revisions only when executed.

I require owner agreement on shared DTOs/errors, auth/session generation/CSRF, account/report lock order, child-edit version propagation, atomic proposal invalidation, discussion rules, queue snapshot adapters, durable budgets/cancellation and SoC activation before integration. I can specify fake-backed adapters while these seams remain open; I claim no backend, frontend, test, deployment or approval from this draft.
