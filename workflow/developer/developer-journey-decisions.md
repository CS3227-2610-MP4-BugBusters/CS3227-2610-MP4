# Developer investigation journey: decisions

These decisions record the Developer owner's choices for future specifications. They do not mean the features are implemented or that shared interfaces have been integrated. The Reporter owner defines Reporter-authored fields and editing rules; the exact fields and cross-role contracts still need alignment.

## Viewing and dashboard

- The active Developer can open reports currently assigned to them. A former assignee loses access on subsequent requests. Closed reports remain visible while still assigned, with investigation actions read-only.
- The dashboard shows assigned report status, search, status filters and pagination. Its visual template follows the team's shared design once agreed.
- The dashboard should provide notifications for a new assignment, an assignment removed by reassignment, a returned resolution and a reopened report. For reopening, notify the previously assigned Developer with a brief message that contains no report contents. This notification does not restore access: the Developer can open the report again only after reassignment and authorization.
- Notification persistence, read/unread controls, delivery timing, retry, retention and cross-role event ownership require a separate specification. Do not include protected report content in a notification shown after access is lost.

## Investigation and private notes

- In addition to the checklist, the Developer has one editable and deletable private notes field per report. The currently assigned Developer can read and edit it; after reassignment the new assignee inherits it and the former assignee loses access. Reporter and Maintainer cannot read the private notes.
- Use version checks to prevent silent overwrites. Private note edits during resolution review leave the pending proposal intact, consistent with private checklist Save; reconcile this with the Maintainer lifecycle spec before implementation.
- The currently assigned Developer may start an investigation by moving Assigned to In progress. They cannot close or reopen reports; those actions belong to the Maintainer.

## Checklist, responses and resolution

- The Developer saves checklist drafts privately and explicitly shares the saved steps. Each step has a stable ID; Reporter evidence/responses are plain text, subject to the Reporter owner's contract. Checklist completion controls are deferred.
- Reporter responses and any AI assessments must be linked to the relevant checklist/response versions; an assessment becomes outdated when its inputs change. The exact Reporter response UI/API is a shared dependency.
- A resolution proposal requires resolution text and changes In progress to Resolution proposed. A stale version is rejected while preserving unsent text. If the Maintainer returns the proposal, show the recorded reason and allow a new proposal. A withdraw-and-revise action is the preferred approach; its lifecycle contract requires Maintainer alignment.

## AI and unresolved shared decisions

- Checklist drafting is the Developer AI feature. Generated steps are advisory and require Developer review before saving or sharing; AI cannot change status or approve a resolution. Recheck access and source versions after generation; discard or mark stale results according to the approved feature contract.
- Whether AI response assessment is required in the release is still a team decision. Report-authored fields, notification integration and the shared dashboard template also remain to be agreed with the respective owners.
