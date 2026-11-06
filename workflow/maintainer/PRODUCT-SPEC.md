# My Maintainer product specification

I record revision MNT-SPEC-1 on 7 November 2026 as a draft for team review. I claim no implementation, verification, or team approval. I derive scope from the MP4 assignment and the active project-plan sections 3–8. I retain the plan's lifecycle conditions as proposals pending team agreement.

## My scope

I own the Maintainer interface, role behavior, AI queue summary, permissions, and tests. I coordinate shared authentication, lifecycle persistence, discussion, and AI interfaces with their owners; I do not assign shared ownership through this document. I build a new MP4 application using the planned React, Java, and PostgreSQL stack.

I view all reports and their investigation history, triage reports, discuss them, review proposals, and control closure/reopening. I cannot edit Reporter report details or Developer notes/checklist instructions, propose resolutions, provision accounts through this role feature, or let AI change records. I exclude repository integration, execution of code, attachments, email, and automatic fixes.

## My requirements and acceptance criteria

| Requirement | My behavior | My acceptance criteria |
| --- | --- | --- |
| MNT-READ-01 | I offer all-report lists, intersecting status/priority/assignee filters, report detail, investigation/checklist/response visibility, and assignment/status history. | Q1: I show correct intersections, stable ordering and an empty result; I expose only safe fields; I reject inactive, unauthenticated and wrong-role requests without report metadata. |
| MNT-TRIAGE-01 | I set a report's priority through an explicit versioned operation. I propose LOW, MEDIUM, HIGH and CRITICAL with MEDIUM initially; I require team agreement on this vocabulary. | T1: I preserve unrelated report content and prior history, increment the aggregate version, and append priority-change history; I reject unknown priorities and stale versions without partial writes. |
| MNT-ASG-01 | I assign an active Developer from Submitted to Assigned atomically. I reassign only Assigned/In progress reports to a different active Developer, record a reason, and return to Assigned. | A1: I reject inactive/wrong-role targets and invalid states; I preserve notes/checklist/history; I make the old Developer lose access at commit and require current-assignment checks on subsequent operations. |
| MNT-RES-01 | I inspect the current proposal before closure. I close only Resolution proposed with the reviewed proposal/version still current. I return a proposal to In progress with a reason and supersede it. I reopen Closed to Submitted with a reason, clear current assignment/proposal, and retain history. | R1: I reject missing reasons, unreconciled versions, absent/superseded proposals and invalid transitions; I record each successful transition atomically; I require a fresh proposal after invalidation before closure. |
| MNT-DISC-01 | I add plain-text discussion to any report and read existing discussion through the shared discussion contract. I propose allowing comments on Closed reports, without changing status or proposal validity. | D1: I show author/time and retain discussion; I reject blank/oversized content and actor spoofing; I leave lifecycle unchanged. |
| MNT-AI-01 | I request an advisory SoC summary for the selected unresolved queue, with supporting report IDs, blockers, and missing information. I display exactly which reports were included/excluded by context limits. | S1: I send only authorized selected fields, use deterministic bounded selection, and accept references only to supplied IDs. I show uncertainty, capture time and a review label; I make zero provider calls for an empty queue. |
| MNT-SEC-01 | I enforce active Maintainer authorization server-side and expected versions for writes. I coordinate atomic source edits/proposal invalidation across roles. | X1: I deny forged role/actor fields; I preserve data on stale requests; I serialize competing closure/edit/reassignment operations so only eligible writes succeed and history matches committed state. |
| MNT-AI-SEC-01 | I treat stored report content and generated content as untrusted, bound resource use, and recheck access, queue membership and versions after generation and before reuse. | S2: I reject invalid IDs/JSON and stale results without generated content; I render plain text; I handle injection, quotas, duplicate clicks, timeout and provider failure without domain writes or losing entered work. |

## My lifecycle integration conditions

I use Submitted, Assigned, In progress, Resolution proposed and Closed as the proposed shared lifecycle. I leave Assigned → In progress and In progress → Resolution proposed to the current assigned Developer. I require current assignment, active account and expected version checks there.

I require authorized Reporter detail/response/evidence edits and Developer note/checklist edits during Resolution proposed to atomically supersede the proposal and return the report to In progress. I retain the proposal for history. I do not treat clarification requests alone as status changes. I propose keeping priority changes and ordinary comments from invalidating a proposal, while incrementing the report aggregate version so stale closure and summaries still fail conservatively.

## My screens and failure behavior

I provide a queue screen with filters, row links and summary generation; a report screen with read-only source/investigation/history, priority, assignment, discussion and eligible lifecycle actions; and a summary panel with included/excluded lists and source links derived from validated IDs.

I show loading separately from an empty queue and disable duplicate submissions while pending. I preserve typed reasons/comments when validation, stale-write or transport errors occur. I ask the user to reload and reconcile after a conflict instead of overwriting stored data. I show session/access failures without generated text, AI errors with a retry opportunity, and an empty-queue explanation without generation. I keep ordinary report workflows available when AI fails. I label a displayed summary with its snapshot time; I require regeneration after a refresh discovers changed sources and never present it as live state.

## My decisions still requiring integration agreement

I seek team agreement on lifecycle conditions, priority vocabulary, Closed-report comments, pagination, report aggregate version propagation, snapshot/recheck semantics, auth/session/CSRF conventions, shared error fields, AI durable budgets and the SoC model/API/quota. I specify concrete proposals in [my technical specification](TECHNICAL-SPEC.md). I track task dependencies in [my plan](PLAN.md) and verification cases in [my test matrix](TEST-MATRIX.md); I do not treat a draft as approval to implement shared interfaces.
