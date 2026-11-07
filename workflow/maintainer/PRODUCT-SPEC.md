# Maintainer product specification

Status: full-role baseline MNT-SPEC-1 remains a draft for team review, 7 November 2026. The isolated read-only frontend increment is implemented and independently checked; shared-service integration and other role features remain pending. Derive scope from the MP4 assignment and the active project-plan sections 3-8. Retain the plan's lifecycle conditions as proposals pending team agreement.

## Scope

My scope covers the Maintainer interface, role behavior, AI queue summary, permissions, and tests. Coordinate shared authentication, lifecycle persistence, discussion, and AI interfaces with their owners; do not assign shared ownership through this document. Build a new MP4 application using the planned React, Java, and PostgreSQL stack.

View all reports and their investigation history, triage reports, discuss them, review proposals, and control closure/reopening. Cannot edit Reporter report details or Developer notes/checklist instructions, propose resolutions, provision accounts through this role feature, or let AI change records. Exclude repository integration, execution of code, attachments, email, and automatic fixes.

## Requirements and acceptance criteria

| Requirement | Behavior | Acceptance criteria |
| --- | --- | --- |
| MNT-READ-01 | Offer all-report lists, intersecting status/priority/assignee filters, report detail, investigation/checklist/response visibility, and assignment/status history. | Q1: Show correct intersections, stable ordering and an empty result; expose only safe fields; reject inactive, unauthenticated and wrong-role requests without report metadata. |
| MNT-TRIAGE-01 | Set a report's priority through an explicit versioned operation. Propose LOW, MEDIUM, HIGH and CRITICAL with MEDIUM initially; require team agreement on this vocabulary. | T1: Preserve unrelated report content and prior history, increment the aggregate version, and append priority-change history; reject unknown priorities and stale versions without partial writes. |
| MNT-ASG-01 | Assign an active Developer from Submitted to Assigned atomically. Reassign only Assigned/In progress reports to a different active Developer, record a reason, and return to Assigned. | A1: Reject inactive/wrong-role targets and invalid states; preserve notes/checklist/history; make the old Developer lose access at commit and require current-assignment checks on subsequent operations. |
| MNT-RES-01 | Inspect the current proposal before closure. Close only Resolution proposed with the reviewed proposal/version still current. Return a proposal to In progress with a reason and supersede it. Reopen Closed to Submitted with a reason, clear current assignment/proposal, and retain history. | R1: Reject missing reasons, unreconciled versions, absent/superseded proposals and invalid transitions; record each successful transition atomically; require a fresh proposal after invalidation before closure. |
| MNT-DISC-01 | Add plain-text discussion to any report and read existing discussion through the shared discussion contract. Propose allowing comments on Closed reports, without changing status or proposal validity. | D1: Show author/time and retain discussion; reject blank/oversized content and actor spoofing; leave lifecycle unchanged. |
| MNT-AI-01 | Request an advisory SoC summary for the selected unresolved queue, with supporting report IDs, blockers, and missing information. Display exactly which reports were included/excluded by context limits. | S1: Send only authorized selected fields, use deterministic bounded selection, and accept references only to supplied IDs. Show uncertainty, capture time and a review label; make zero provider calls for an empty queue. |
| MNT-SEC-01 | Enforce active Maintainer authorization server-side and expected versions for writes. Coordinate atomic source edits/proposal invalidation across roles. | X1: Deny forged role/actor fields; preserve data on stale requests; serialize competing closure/edit/reassignment operations so only eligible writes succeed and history matches committed state. |
| MNT-AI-SEC-01 | Treat stored report content and generated content as untrusted, bound resource use, and recheck access, queue membership and versions after generation and before reuse. | S2: Reject invalid IDs/JSON and stale results without generated content; render plain text; handle injection, quotas, duplicate clicks, timeout and provider failure without domain writes or losing entered work. |

## Lifecycle integration conditions

Use Submitted, Assigned, In progress, Resolution proposed and Closed as the proposed shared lifecycle. Leave Assigned -> In progress and In progress -> Resolution proposed to the current assigned Developer. Require current assignment, active account and expected version checks there.

Require authorized Reporter detail/response/evidence edits and Developer note/checklist edits during Resolution proposed to atomically supersede the proposal and return the report to In progress. Retain the proposal for history. Do not treat clarification requests alone as status changes. Propose keeping priority changes and ordinary comments from invalidating a proposal, while incrementing the report aggregate version so stale closure and summaries still fail conservatively.

## Screens and failure behavior

Provide a queue screen with filters, row links and summary generation; a report screen with read-only source/investigation/history, priority, assignment, discussion and eligible lifecycle actions; and a summary panel with included/excluded lists and source links derived from validated IDs.

Show loading separately from an empty queue and disable duplicate submissions while pending. Preserve typed reasons/comments when validation, stale-write or transport errors occur. Ask the user to reload and reconcile after a conflict instead of overwriting stored data. Show session/access failures without generated text, AI errors with a retry opportunity, and an empty-queue explanation without generation. Keep ordinary report workflows available when AI fails. Label a displayed summary with its snapshot time; require regeneration after a refresh discovers changed sources and never present it as live state.

## Decisions still requiring integration agreement

Seek team agreement on lifecycle conditions, priority vocabulary, Closed-report comments, pagination, report aggregate version propagation, snapshot/recheck semantics, auth/session/CSRF conventions, shared error fields, AI durable budgets and the SoC model/API/quota. Specify concrete proposals in [technical specification](TECHNICAL-SPEC.md). Track task dependencies in [plan](PLAN.md) and verification cases in [test matrix](TEST-MATRIX.md); do not treat a draft as approval to implement shared interfaces.
